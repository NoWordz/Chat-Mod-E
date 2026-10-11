#!/usr/bin/env python3
"""校验 versions/ 里的目标矩阵 —— 本地与 CI 跑的是同一份。

结构照搬 AtomChat 的同名脚本。目标写进 versions/targets.json，本脚本是那句声明的执行者：
  * 矩阵的规则（每个 Minecraft 版本都要有 Fabric / NeoForge / Forge 三条）
  * 映射家族与映射层的双向约束（有两个在建目标的家族必须有层；有层的目标必须挂层）
  * buildable: true 的目标不许留 unverified
  * 工程目录存在就必须在矩阵里，反之亦然（不许有"没有任何地方编"的源码）
  * 各平台不许自己定义身份键（mod_version 等只在仓库根那一份）
  * 矩阵里写的 java / gradle 与工程里实际的 toolchain / wrapper 一致（防止两边各写一份然后漂移）
  * 映射层的每个文件在另一映射家族上必须有同路径孪生，且不许逐字相同（逐字相同 = 本该进 shared/）
  * 钉等清单（versions/pinned-equal.json）里的多平台副本必须逐字相同
  * 共享层不许用高于最低目标 Java 级别的语法（模式匹配 switch）
  * 服务端侧的类不许引用客户端类型
  * 依赖方向：shared/ 只许引 shared/；layers/ 只许引 shared/layers，或三端同路径都存在的类

用法：
  python tools/verify_targets.py                       # 只校验，打印摘要
  python tools/verify_targets.py --matrix-out m.json   # 顺便把 CI 矩阵写成 JSON
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import struct
import sys
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOADERS = ["Fabric", "NeoForge", "Forge"]
REQUIRED_FIELDS = ["minecraft", "loader", "mappings", "java", "gradle", "project", "buildable", "layers"]
IDENTITY_KEYS = [
    "mod_id", "mod_name", "mod_license", "mod_group_id",
    "mod_authors", "mod_description", "mod_version",
]
OWN_PREFIX = "com.niuqu.chatbubble."

errors: list[str] = []
notes: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def load_json(rel: str) -> dict:
    path = ROOT / rel
    if not path.is_file():
        fail(f"{rel} 不存在")
        return {}
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except json.JSONDecodeError as exc:
        fail(f"{rel} 不是合法 JSON：{exc}")
        return {}


def entries_of(raw: dict) -> dict:
    return {k: v for k, v in raw.items() if not k.startswith("_")}


def read_props(path: pathlib.Path) -> dict:
    out = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def check_root_identity() -> None:
    props = read_props(ROOT / "gradle.properties")
    if not props:
        fail("仓库根的 gradle.properties 不存在或读不出键 —— 身份没有来源")
        return
    missing = [k for k in IDENTITY_KEYS if k not in props]
    if missing:
        fail(f"仓库根的 gradle.properties 缺身份键：{', '.join(missing)}")


def check_targets(targets: dict, layers: dict, aliases: dict) -> None:
    if not targets:
        fail("versions/targets.json 里一个目标都没有 —— 文件坏了或被改空了")
        return

    mappings = {}
    seen_combo = set()

    for name, t in targets.items():
        for field in REQUIRED_FIELDS:
            if field not in t:
                fail(f"目标 {name} 缺字段 {field}")
        if any(f not in t for f in REQUIRED_FIELDS):
            continue

        mc, loader = t["minecraft"], t["loader"]
        seen_combo.add((mc, loader))
        mappings.setdefault(t["mappings"], []).append(name)

        for layer in t["layers"]:
            if layer not in layers:
                fail(f"目标 {name} 引用了 layers.json 里没有的层：{layer}")
                continue
            gap = predicate_gap(t, layer, layers[layer])
            if gap:
                fail(f"目标 {name} 挂了层 {layer}，但它满足不了该层的谓词：{gap}")

        project = ROOT / t["project"]
        exists = project.is_dir()

        if t["buildable"] and not exists:
            fail(f"目标 {name} 声明 buildable: true，但工程目录 {t['project']} 不存在")
        if t["buildable"] and t.get("unverified"):
            fail(f"目标 {name} 是 buildable: true，却还留着 unverified：{t['unverified']}")
        if exists:
            for needed in ("build.gradle", "settings.gradle", "gradlew"):
                if not (project / needed).is_file():
                    fail(f"目标 {name} 的工程目录缺 {needed}")
            check_platform_identity(name, project)
            check_platform_toolchain(name, project, t)

    # 映射家族不止一个时（本仓就是：Fabric 走 Yarn，另两端走官方名），
    # 每个【有两个以上成员在建】的家族都要有对应的映射层 —— 那种家族里的平行副本是可共享的，
    # 让它们各存一份就是把「三端同步」缩小成「两端同步」。只有一个成员的家族不必成层（没人可共享）；
    # 层存在时，未挂该层的目标要在自己平台目录里保留同路径副本，那份副本由孪生检查盯着。
    if len(mappings) > 1:
        for family in sorted(mappings):
            built = [n for n in mappings[family] if (ROOT / targets[n]["project"]).is_dir()]
            declared = [n for n, d in layers.items()
                        if layer_axes(d) == ["mappings"] and d.get("mappings") == family]
            if len(built) > 1 and not declared:
                fail(f"mappings={family} 有 {len(built)} 个在建目标（{', '.join(built)}），"
                     f"但 layers.json 里没有纯映射层声明 mappings={family} —— "
                     f"它们本可共用一份，现在只能各存一份没人盯着的手抄副本")
                continue
            for layer_name in declared:
                check_mapping_twins(layer_name, layers[layer_name], targets, family,
                                    aliases.get(f"layers/mapping/{layer_name}", {}))

    # 反方向：某一家族已经有映射层了，同家族的目标就必须挂它 —— 否则它会留一份私藏副本，
    # 而那份副本与层里的内容本应逐字相同，没有任何东西会告诉你它慢慢不一样了。
    for name, t in targets.items():
        for layer_name, d in layers.items():
            if layer_axes(d) != ["mappings"] or d.get("mappings") != t.get("mappings"):
                continue
            if layer_name not in t["layers"]:
                fail(f"目标 {name} 的 mappings 是 {t.get('mappings')}，映射层 {layer_name} 就是为这个家族准备的，"
                     f"但它的 layers 里没有这一层 —— 那样它会留一份没人盯着的私藏副本")

    # 矩阵是规则：每个出现过的 Minecraft 版本都要凑齐三个加载器
    mcs = sorted({mc for mc, _ in seen_combo})
    for mc in mcs:
        for loader in LOADERS:
            if (mc, loader) not in seen_combo:
                fail(f"Minecraft {mc} 缺 {loader} 条目 —— 新增一个版本 = 加三条；实在不做也要写 "
                     f"buildable: false 并在 note 里写清为什么，缺了要在这里看得见")

    # 工程目录存在就必须在矩阵里
    platforms = ROOT / "platforms"
    if platforms.is_dir():
        declared = {t["project"] for t in targets.values() if isinstance(t.get("project"), str)}
        for child in sorted(p for p in platforms.iterdir() if p.is_dir()):
            rel = f"platforms/{child.name}"
            if rel not in declared:
                fail(f"{rel} 存在但不在 targets.json 里 —— 那样它是一份没有任何地方编的源码")


def check_platform_identity(name: str, project: pathlib.Path) -> None:
    props = read_props(project / "gradle.properties")
    dupes = [k for k in IDENTITY_KEYS if k in props]
    if dupes:
        fail(f"目标 {name} 的 gradle.properties 自己定义了身份键：{', '.join(dupes)} —— "
             f"身份只在仓库根那一份里，这里留一份就会各自漂移")


def check_platform_toolchain(name: str, project: pathlib.Path, t: dict) -> None:
    text = (project / "build.gradle").read_text(encoding="utf-8", errors="replace")
    m = re.search(r"JavaLanguageVersion\.of\((\d+)\)", text)
    if m and int(m.group(1)) != int(t["java"]):
        fail(f"目标 {name} 的 targets.json 写 java={t['java']}，"
             f"但 build.gradle 的 toolchain 是 {m.group(1)}")

    wrapper = project / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if wrapper.is_file():
        m = re.search(r"gradle-([0-9][^-]*)", wrapper.read_text(encoding="utf-8", errors="replace"))
        if m and m.group(1) != str(t["gradle"]):
            fail(f"目标 {name} 的 targets.json 写 gradle={t['gradle']}，"
                 f"但 wrapper 是 {m.group(1)}")


def _root_of(line: str) -> str:
    """从「包名  — 说明」这种声明行里取出包名。"""
    return line.split(" — ")[0].strip()


def layer_axes(d: dict) -> list:
    return [a for a in ("since", "loader", "mappings") if a in d]


def layer_dir(layer_name: str, d: dict) -> pathlib.Path:
    """目录由声明的轴推出来 —— 声明与目录不会互相矛盾。"""
    axis_dirs = {"since": "version", "loader": "loader", "mappings": "mapping"}
    return ROOT / "layers" / "+".join(sorted(axis_dirs[a] for a in layer_axes(d))) / layer_name


def version_at_least(have: str, want: str) -> bool:
    def parts(v):
        return [int(p) if str(p).isdigit() else 0 for p in str(v).split(".")]

    a, b = parts(have), parts(want)
    for i in range(max(len(a), len(b))):
        x, y = (a[i] if i < len(a) else 0), (b[i] if i < len(b) else 0)
        if x != y:
            return x > y
    return True


def predicate_gap(t: dict, layer_name: str, d: dict):
    """目标挂了这个层，但它满足不了该层的谓词 —— 返回原因，满足则 None。"""
    for axis in layer_axes(d):
        want = str(d[axis])
        if axis == "since":
            if not version_at_least(t["minecraft"], want):
                return f"Minecraft {t['minecraft']} 够不到 since {want}"
        else:
            have = str(t.get(axis))
            if have != want:
                return f"它的 {axis} 是 {have}，层要求 {axis}={want}"
    return None


def digest(path: pathlib.Path) -> str:
    """行尾无关的比较。

    工作区是 CRLF、仓库里存的是 LF（`.gitattributes` 的 `* text=auto` 管这件事），
    所以直读字节会把"只有行尾不同"判成内容不同 —— 那样"孪生副本逐字相同"这条检查
    会假阴性（该报的不报）。比较前先折叠行尾，让这条检查只看内容。
    """
    return hashlib.sha1(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def check_mapping_twins(layer_name: str, d: dict, targets: dict, family: str, aliases: dict) -> None:
    """映射层的代价是「另一个家族保留同路径的孪生副本」。这份副本是手抄的，
    所以必须有人在盯着：少一个、或者其实逐字相同（那它本该进 shared/）都要红。

    aliases 是路径别名：同一个类在两个家族上住不同包时（比如 Fabric 侧为了包私有
    访问故意住进原版包），把对应关系写在这里，检查照样跑，找不到一样红 ——
    别名不是豁免，只是让例外变成可检查的声明。"""
    base = layer_dir(layer_name, d)
    if not base.is_dir():
        return
    files = [p.relative_to(base).as_posix() for p in base.rglob("*.java")]
    for rel in sorted(files):
        if not rel.startswith(("src/main/java/", "src/test/java/")):
            continue
        twin_rel = aliases.get(rel, rel)
        for name, t in targets.items():
            if t.get("mappings") == family:
                continue
            project = ROOT / t["project"]
            if not project.is_dir():
                continue  # 还没建起来的目标不欠副本
            twin = project / twin_rel
            if not twin.is_file():
                hint = f"（按别名找的是 {twin_rel}）" if twin_rel != rel else ""
                fail(f"映射层 {layer_name} 里有 {rel}，但目标 {name}"
                     f"（mappings={t.get('mappings')}）的平台目录里没有对应的孪生副本{hint} —— "
                     f"这条路线的代价就是那份平行副本，少了它意味着这个类在那个目标上根本不存在")
            elif digest(twin) == digest(base / rel):
                fail(f"{rel} 的孪生副本与映射层里那份【逐字相同】—— 它其实是映射中立的，"
                     f"应该进 shared/，不必在这里共存两份")


def check_pinned_equal(pinned: dict) -> None:
    """钉等清单：同一路径在多个平台各有一份，内容必须逐字相同（行尾无关）。

    清单里的每一项都是「本该进 shared/ 或映射层，暂时进不去」的显式状态 ——
    有闸盯着，它们才不会悄悄漂移。哪天消掉一条，就从清单里删掉它。

    条目可以是字符串（全部平台互等），也可以是 {"path": ..., "platforms": [...]} ——
    后者用于 Fabric 有 Yarn 孪生、内容本就不同的文件：只在列出的平台之间要求逐字相同。

    单拷贝必须失败（L17）：某个被钉的**测试**文件在某平台整个缺失时，旧实现是 `continue`
    静默跳过 —— 那份平台就悄悄豁免了。只要该平台工程目录还在，缺件就是红。"""
    items = pinned.get("pinned", [])
    if not items:
        fail("versions/pinned-equal.json 的 pinned 清单是空的 —— 要么这条制度废了，要么布局变了")
        return
    all_platforms = sorted(p.name for p in (ROOT / "platforms").iterdir() if p.is_dir())
    seen_items = set()
    for item in items:
        if isinstance(item, str):
            rel, scope = item, None
        elif isinstance(item, dict) and isinstance(item.get("path"), str):
            rel, scope = item["path"], item.get("platforms")
            if not isinstance(scope, list) or not scope:
                fail(f"钉等清单 {rel} 的对象条目缺少非空 platforms 列表 —— 限定不了范围就等于没钉")
                continue
        else:
            fail(f"钉等清单有条目既不是字符串也不是 {{path, platforms}} 对象：{item!r}")
            continue
        key = (rel, tuple(scope) if scope is not None else None)
        if key in seen_items:
            fail(f"钉等清单里的 {rel}{'（限定平台：' + ', '.join(scope) + '）' if scope else ''} 重复出现 —— "
                 f"同一条被钉两次，说明清单腐坏了")
            continue
        seen_items.add(key)
        platforms = [p for p in (scope if scope is not None else all_platforms) if p in all_platforms]
        if len(platforms) < 2:
            fail(f"钉等清单里的 {rel} 只落在 {len(platforms)} 个平台 —— 少于两份就没有「漂移」可言，"
                 f"这条应该从清单删掉，而不是留一条永远绿的")
            continue
        copies = {p: ROOT / "platforms" / p / rel for p in platforms if (ROOT / "platforms" / p / rel).is_file()}
        scope_note = "" if scope is None else "（限定平台：" + ", ".join(scope) + "）"
        # 列出的平台只要工程目录还在，就必须有一份 —— 缺了一份是红，不是静默跳过（L17）
        for p in platforms:
            if (ROOT / "platforms" / p).is_dir() and p not in copies:
                fail(f"钉等清单里的 {rel}{scope_note} 在目标 {p} 上不存在 —— "
                     f"被钉的文件缺了一份，那份平台就悄悄豁免了；要么补齐，要么从清单删掉")
        if len(copies) < 2:
            continue  # 缺件已在上面报红，这里不再叠报
        digests = {p: digest(f) for p, f in copies.items()}
        distinct = {v for v in digests.values()}
        if len(distinct) > 1:
            where = ", ".join(sorted(digests))
            fail(f"钉等清单里的 {rel}{scope_note} 在各平台内容不一致（{where}）—— "
                 f"要么把改动同步到每一份，要么把它收编进 shared//映射层然后从清单删掉")


def check_layers(layers: dict) -> None:
    if not layers:
        fail("versions/layers.json 里一层都没有")
        return
    for name, d in layers.items():
        axes = layer_axes(d)
        if not axes:
            fail(f"层 {name} 一个轴都没钉（since / loader / mappings）—— 一个都不写就是 shared/，不是层")
        if not isinstance(d.get("populated"), bool):
            fail(f"层 {name} 没有声明 populated（true/false）—— 「空」要是显式声明的状态")
        directory = layer_dir(name, d)
        if d.get("populated") and not directory.is_dir():
            fail(f"层 {name} 声明 populated: true，但目录 {directory.relative_to(ROOT).as_posix()} 不存在")
        if directory.is_dir() and not d.get("populated"):
            files = [p for p in directory.rglob("*") if p.is_file()]
            if files:
                fail(f"层 {name} 声明 populated: false，但目录里有 {len(files)} 个文件 —— "
                     f"要么翻成 true，要么把内容挪走")


def check_resource_paths(paths: dict) -> None:
    """逐目标比对资源落点：着色器命名空间、语言文件、显式列出的必备文件。

    放错命名空间不会报错，只会在运行期静默降级（圆角没了 / 界面退回英文），
    所以这里按声明逐项相等来卡 —— 多一个少一个都红。
    """
    targets = paths.get("targets", {})
    if not targets:
        fail("versions/resource-paths.json 缺 targets")
        return
    names = paths.get("shader_names", [])
    exts = paths.get("shader_extensions", [])
    langs = paths.get("languages", [])
    also = paths.get("also_required", [])

    for name, t in targets.items():
        res = ROOT / "platforms" / name / "src/main/resources"
        if not res.is_dir():
            continue  # 还没建起来的目标
        expected = set(also)
        for shader in names:
            for ext in exts:
                expected.add(f"{t['shaders_root']}/{shader}{ext}")
        for lang in langs:
            expected.add(f"{t['lang_root']}/{lang}.json")

        # 只比声明的两类根（着色器 / 语言）与显式列出的文件 —— 元数据与 mixin 配置
        # （fabric.mod.json、mixins.e33chat.json）不属于这里，按「所有 .json」过滤
        # 会把它们算成「多出来的资源」。
        def interesting(rel: str) -> bool:
            return ("/shaders/" in rel or "/lang/" in rel or rel in also)

        actual = {p.relative_to(res).as_posix() for p in res.rglob("*")
                  if p.is_file() and interesting(p.relative_to(res).as_posix())}
        for missing in sorted(expected - actual):
            fail(f"目标 {name} 缺资源 {missing} —— 放错命名空间是静默失效，不报错")
        for extra in sorted(actual - expected):
            fail(f"目标 {name} 多出未声明的资源 {extra} —— 写进 versions/resource-paths.json，"
                 f"或删掉它（多出来的资源不会被加载，只会让人以为生效了）")


# ---------------------------------------------------------------------------
# PNG decoding (stdlib only) — the texture checks compare DECODED PIXELS, not the
# encoded PNG bytes. Re-encoding with identical pixels must read equal; any pixel
# change must read different. See M14/L15 in _e33_250/audit/E33CHAT-250-AUDIT.md.
# ---------------------------------------------------------------------------

PNG_SIG = b"\x89PNG\r\n\x1a\n"


def decode_png(path: pathlib.Path):
    """Decode a PNG to (width, height, RGBA bytes). Supports the 8-bit RGBA files this
    repo ships; returns None on any unsupported/corrupt file so the caller turns that
    into a red rather than crashing the whole guard."""
    try:
        b = path.read_bytes()
    except OSError:
        return None
    if b[:8] != PNG_SIG:
        return None
    pos, idat = 8, b""
    w = h = bit_depth = color_type = interlace = None
    while pos + 12 <= len(b):
        (ln,) = struct.unpack(">I", b[pos:pos + 4])
        typ = b[pos + 4:pos + 8]
        data = b[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b"IHDR":
            w, h, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(">IIBBBBB", data)
        elif typ == b"IDAT":
            idat += data
        elif typ == b"IEND":
            break
    if None in (w, h) or bit_depth != 8 or color_type != 6 or interlace != 0:
        return None
    try:
        raw = zlib.decompress(idat)
    except zlib.error:
        return None
    stride = w * 4
    if len(raw) != (stride + 1) * h:
        return None
    out = bytearray()
    prev = bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if f == 1:
            for x in range(4, stride):
                line[x] = (line[x] + line[x - 4]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                left = line[x - 4] if x >= 4 else 0
                line[x] = (line[x] + ((left + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                c = prev[x - 4] if x >= 4 else 0
                p = a + prev[x] - c
                pa, pb, pc = abs(p - a), abs(p - prev[x]), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (prev[x] if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        elif f != 0:
            return None
        out += line
        prev = line
    return w, h, bytes(out)


def png_pixel_sha(path: pathlib.Path):
    """sha1 of the decoded pixels (dimensions + RGBA), or None when undecodable."""
    decoded = decode_png(path)
    if decoded is None:
        return None
    w, h, rgba = decoded
    return hashlib.sha1(f"{w}x{h}:".encode() + rgba).hexdigest()


# ---------------------------------------------------------------------------
# Texture reference scanning + derived hover pairs
# ---------------------------------------------------------------------------

TEXTURE_SRC_GLOBS = ("shared/src", "layers", "platforms/*/src/main/java")
UI_ELEMENT_CONST_RE = re.compile(r'[A-Z][A-Z0-9_]*\("([a-z0-9_]+)"\)')
UI_ELEMENT_ENUM_RE = re.compile(r"enum\s+UiElement\s*\{(.*?);", re.S)
ICON_TEX_RE = re.compile(r"iconTex\s*\(")
STR_LITERAL_RE = re.compile(r'"(?:[^"\\]|\\.)*"')
UI_ELEMENT_REF_RE = re.compile(r"UiElement\.([A-Z][A-Z0-9_]*)")
TERNARY_HOVER_RE = re.compile(
    r'\?\s*[^?:]*?UiElement\.([A-Z][A-Z0-9_]*)[^?:]*?:\s*[^?:]*?UiElement\.([A-Z][A-Z0-9_]*)')


def _enum_constant_to_path(text: str) -> dict:
    m = UI_ELEMENT_ENUM_RE.search(text)
    return dict(re.findall(r'([A-Z][A-Z0-9_]*)\("([a-z0-9_]+)"\)', m.group(1))) if m else {}


def _ui_element_names(path: pathlib.Path) -> set:
    """UiElement enum constant区里的 "path" 字面量。解析出 0 个就是红 —— 枚举体改了形状，
    被引用纹理集合会静默缩小，界面图标静默空白。"""
    if not path.is_file():
        fail(f"{path.relative_to(ROOT).as_posix()} 不存在 —— 纹理名没有来源")
        return set()
    text = path.read_text(encoding="utf-8", errors="replace")
    m = UI_ELEMENT_ENUM_RE.search(text)
    if not m:
        fail(f"{path.relative_to(ROOT).as_posix()} 里找不到 enum UiElement 的常量区")
        return set()
    names = set(UI_ELEMENT_CONST_RE.findall(m.group(1)))
    if not names:
        fail(f"{path.relative_to(ROOT).as_posix()} 的 enum UiElement 里一个 \"path\" 字面量都没解析到 —— "
             f"枚举体改了形状，纹理名集合会静默缩小")
    return names


def _brace_end(text: str, open_idx: int) -> int:
    """Index of the ')' matching the '(' at open_idx, or -1."""
    depth = 0
    for i in range(open_idx, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _icon_tex_names(platform: str) -> set:
    """Literals passed to iconTex(...) on this platform. Parentheses are matched so a
    refactor to a variable argument (iconTex(name)) is visible: zero literals scanned
    is a red, not a silently empty expected set."""
    out = set()
    hits = 0
    root = ROOT / "platforms" / platform / "src/main/java"
    for f in root.rglob("*.java") if root.is_dir() else []:
        text = f.read_text(encoding="utf-8", errors="replace")
        for m in ICON_TEX_RE.finditer(text):
            hits += 1
            end = _brace_end(text, m.end() - 1)
            if end < 0:
                continue
            for s in STR_LITERAL_RE.findall(text[m.end():end]):
                inner = s[1:-1]
                if re.fullmatch(r"[a-z0-9_]+", inner):
                    out.add(inner)
    if hits == 0:
        fail(f"目标 {platform}: 一个 iconTex(...) 调用都没扫到 —— 匹配式坏了或布局变了")
    elif not out:
        fail(f"目标 {platform}: 扫到 {hits} 个 iconTex(...) 却没有任何字符串字面量 —— "
             f"调用可能重构成传变量，纹理名集合会静默缩小")
    return out


def _texture_source_texts():
    for g in TEXTURE_SRC_GLOBS:
        for f in ROOT.glob(g):
            it = f.rglob("*.java") if f.is_dir() else [f]
            for j in it:
                if "/test/" in j.as_posix():
                    continue
                yield j.read_text(encoding="utf-8", errors="replace")


def contrast_pairs() -> set:
    """(resting_name, hover_name) pairs the renderers actually draw, derived from call
    sites instead of hardcoded:

      * a `cond ? X : Y` ternary that draws a `*_hover` UiElement (resting -> hover);
      * the `_bg`/`_hover` naming pair, gated on both constants being referenced.

    The audit (M13) found the old hardcoded list checked the wrong pairs, so
    `sidebar_bg -> sidebar_hover` (every sidebar row's resting fill) went unguarded."""
    paths = {}
    for p in sorted(ROOT.glob("platforms/*/src/main/java/com/niuqu/chatbubble/texture/UiElement.java")):
        paths.update(_enum_constant_to_path(p.read_text(encoding="utf-8", errors="replace")))
    refs = set()
    pairs = set()
    for text in _texture_source_texts():
        refs.update(UI_ELEMENT_REF_RE.findall(text))
        for a, b in TERNARY_HOVER_RE.findall(text):
            if a not in paths or b not in paths:
                continue
            pa, pb = paths[a], paths[b]
            if pa.endswith("_hover") and not pb.endswith("_hover"):
                pairs.add((pb, pa))   # cond ? HOVER : resting
            elif pb.endswith("_hover") and not pa.endswith("_hover"):
                pairs.add((pa, pb))   # cond ? resting : HOVER
    for name, path in paths.items():
        if path.endswith("_hover"):
            resting = path[:-len("_hover")] + "_bg"
            resting_const = next((n for n, q in paths.items() if q == resting), None)
            if resting_const and resting_const in refs and name in refs:
                pairs.add((resting, path))
    return pairs


REQUIRED_CONTRAST_PAIRS = {
    ("sidebar_bg", "sidebar_hover"),
    ("sidebar_selected", "context_hover"),
    ("sidebar_selected", "close_hover"),
}


def check_ui_textures(targets: dict) -> None:
    """每个目标：被引用的纹理名（UiElement 常量区 ∪ iconTex 字面量）在每个主题目录下都必须
    有 PNG；三端纹理集合逐名相同且**逐像素**相同；悬停态与其静止态**逐像素**必须可区分。

    这类缺陷的现场表现都是静默的：图标空白、三端分叉、hover 不可见。都靠解码像素判，
    不靠 PNG 字节（重编码即可绕过字节比较）。"""
    pairs = contrast_pairs()
    absent = REQUIRED_CONTRAST_PAIRS - pairs
    if absent:
        fail(f"悬停对比对子没有从调用点推导出来：{sorted(absent)} —— "
             f"要么渲染器改了绘制方式，要么推导逻辑坏了（禁止靠硬编码凑齐）")

    per_platform = {}   # platform -> theme -> set(names)
    pixel_sha = {}      # platform -> theme -> {name: pixel sha}
    for name in sorted(targets):
        t = targets[name]
        project = t.get("project") if isinstance(t, dict) else None
        if not project or not (ROOT / project).is_dir():
            continue  # 声明了但还没有工程的目标：没有资源可比
        gui = ROOT / "platforms" / name / "src" / "main" / "resources" / "assets" / "e33chat" / "textures" / "gui"
        if not gui.is_dir():
            fail(f"目标 {name} 没有 assets/e33chat/textures/gui —— 面板/侧栏纹理全没了")
            continue
        themes = sorted(p.name for p in gui.iterdir() if p.is_dir())
        if not themes:
            fail(f"目标 {name} 的 textures/gui 里没有主题目录")
            continue
        expected = _ui_element_names(
            ROOT / "platforms" / name / "src" / "main" / "java" / "com" / "niuqu"
            / "chatbubble" / "texture" / "UiElement.java")
        expected |= _icon_tex_names(name)
        if not expected:
            fail(f"目标 {name}: 被引用纹理名集合是空的 —— 扫描全废，这条检查会变成什么都不查")
            continue
        actual, sha = {}, {}
        for theme in themes:
            files = sorted((gui / theme).glob("*.png"))
            if not files:
                fail(f"目标 {name} 主题目录 {theme} 里一个 PNG 都没有")
            actual[theme] = {p.name[:-4] for p in files}
            sha[theme] = {}
            for p in files:
                d = png_pixel_sha(p)
                if d is None:
                    fail(f"{p.relative_to(ROOT).as_posix()} 解不开（非 8-bit RGBA / 损坏）—— "
                         f"纹理内容无法核对")
                sha[theme][p.name[:-4]] = d
            for missing in sorted(expected - actual[theme]):
                fail(f"目标 {name} 主题 {theme} 缺纹理 {missing}.png —— 引用了却没有文件，界面会静默空白")
        per_platform[name] = actual
        pixel_sha[name] = sha

    # hover vs resting: decoded pixels must differ
    for name, themes in sorted(pixel_sha.items()):
        for theme, files in themes.items():
            for resting, hover in sorted(pairs):
                if resting in files and hover in files and files[resting] == files[hover]:
                    fail(f"目标 {name} 主题 {theme}：{hover}.png 与 {resting}.png 解码后逐像素相同 —— "
                         f"hover 与静止态不可区分，等于没有 hover")

    # cross-platform: same names, same pixels
    names = sorted(per_platform)
    if len(names) < 2:
        fail(f"只有 {len(names)} 个在建目标参与纹理比对 —— 三端一致性无从检查")
        return
    themes_union = {}
    for name in names:
        for theme, nms in per_platform[name].items():
            themes_union.setdefault(theme, set()).update(nms)
    for theme, want in sorted(themes_union.items()):
        for nm in sorted(want):
            holders = [n for n in names if nm in per_platform[n].get(theme, set())]
            if len(holders) < len(names):
                missing = sorted(set(names) - set(holders))
                fail(f"主题 {theme} 的纹理 {nm}.png 缺在 {missing}（{holders[0]} 有）—— "
                     f"某个目标整体缺一个图标，按文件名集合比对会自我豁免")
                continue
            shas = {n: pixel_sha[n][theme][nm] for n in names}
            if len(set(shas.values())) > 1:
                fail(f"主题 {theme} 的纹理 {nm}.png 在各目标解码后内容不同（{', '.join(sorted(shas))}）—— "
                     f"三端会渲染出不同的图标")


# ---------------------------------------------------------------------------
# Language table checks
# ---------------------------------------------------------------------------

# Fabric-only keys: its config screen renders a richer tooltip than Forge/Neo. This is a
# real, narrow difference, not drift. Values are still compared on every shared key.
LANG_FABRIC_ONLY_KEYS = {"e33chat.config.receive_images.tooltip"}
LANG_FABRIC = "1.21.1-fabric"


def check_lang_keys(targets: dict) -> None:
    """语言表：同目标 en/zh 必须同键，跨目标也必须同键，且**同键必须同值**（只比键集合会漏掉
    「翻译被换成英文/空白」与「键被三端同时删掉」）。Fabric 独有的键单列豁免，但仍要求非空。"""
    per_platform = {}  # platform -> locale -> {key: value}
    for name in sorted(targets):
        t = targets[name]
        project = t.get("project") if isinstance(t, dict) else None
        if not project or not (ROOT / project).is_dir():
            continue
        root = ROOT / "platforms" / name / "src" / "main" / "resources" / "assets" / "e33chat" / "lang"
        if not root.is_dir():
            fail(f"目标 {name} 没有 assets/e33chat/lang")
            continue
        tables = {}
        for f in sorted(root.glob("*.json")):
            try:
                tables[f.stem] = json.loads(f.read_text(encoding="utf-8", errors="replace"))
            except ValueError as e:
                fail(f"{f.relative_to(ROOT).as_posix()} 不是合法 JSON：{e}")
        if len(tables) < 2:
            fail(f"目标 {name} 的语言表少于两份（{sorted(tables)}）")
        for locale, kv in tables.items():
            for key, val in kv.items():
                if not isinstance(val, str) or not val.strip():
                    fail(f"目标 {name} 的 {locale}.json 键 {key} 是空值 —— 界面上会什么都不显示")
        union = set().union(*[set(kv) for kv in tables.values()]) if tables else set()
        for locale, kv in tables.items():
            for missing in sorted(union - set(kv)):
                fail(f"目标 {name} 的 {locale}.json 缺键 {missing}")
        per_platform[name] = tables

    names = sorted(per_platform)
    if len(names) < 2:
        fail(f"只有 {len(names)} 个在建目标有语言表 —— 三端一致性无从检查")
        return
    locales = sorted(set().union(*[set(t) for t in per_platform.values()]))
    for locale in locales:
        get = {n: per_platform[n].get(locale, {}) for n in names}
        all_keys = set().union(*[set(v) for v in get.values()])
        for key in sorted(all_keys):
            holders = [n for n in names if key in get[n]]
            if len(holders) < len(names):
                if key in LANG_FABRIC_ONLY_KEYS and set(holders) == {LANG_FABRIC}:
                    continue
                missing = sorted(set(names) - set(holders))
                fail(f"{locale}.json 的键 {key} 缺在 {missing} —— 这些目标上界面会回退成原始键名")
                continue
            values = {n: get[n][key] for n in names}
            if len(set(values.values())) > 1:
                fail(f"{locale}.json 的键 {key} 三端取值不同："
                     f"{ {n: values[n] for n in names} } —— 同一句界面文案三端不一致")


# ---------------------------------------------------------------------------
# Referenced-key check: every key the UI code asks for must exist in the tables
# ---------------------------------------------------------------------------

# A translation key literal in Java: a double-quoted "e33chat.<...>" string. Resource
# locations use a colon (e33chat:textures/...) so the dotted form only ever appears as a
# lang key (verified: every dotted literal in the tree is present in the tables).
# Literals ending in "." are prefix concatenations ("e33chat.theme." + NAME); they are
# checked as a namespace instead of an exact key.
UI_LANG_KEY_RE = re.compile(r'"(e33chat\.[A-Za-z0-9_.]+)"')
UI_LANG_EXTRA_ROOTS = ["shared/src/main/java", "layers"]


def collect_referenced_lang_keys(targets: dict) -> tuple[set, set]:
    """All "e33chat.*" string literals the client UI code references: exact keys plus the
    prefix namespaces built by string concatenation. Sources scanned are each buildable
    target's src/main/java, plus shared/ and layers/ (they compile into every target)."""
    roots = []
    for name in sorted(targets):
        t = targets[name]
        if not isinstance(t, dict):
            continue
        project = t.get("project")
        if project and (ROOT / project).is_dir():
            roots.append(ROOT / project / "src" / "main" / "java")
    roots += [ROOT / r for r in UI_LANG_EXTRA_ROOTS]
    exact: set = set()
    prefix: set = set()
    for root in roots:
        if not root.is_dir():
            continue
        for f in root.rglob("*.java"):
            posix = f.as_posix()
            if "/build/" in posix or "/src/test/" in posix:
                continue
            text = f.read_text(encoding="utf-8", errors="replace")
            for m in UI_LANG_KEY_RE.finditer(text):
                key = m.group(1)
                (prefix if key.endswith(".") else exact).add(key)
    return exact, prefix


def check_lang_references(targets: dict) -> None:
    """界面代码引用的每个键都必须在语言表里存在。缺键时 MC 会把原始键名画到界面上
    （"文本暴露了包名"），这正是要拦住的 bug —— 只比语言表之间的键集合是抓不到的，
    因为三端可以一起缺同一个键。前缀拼接（"e33chat.theme." + NAME）只要求命名空间下
    至少有一个键。"""
    exact, prefix = collect_referenced_lang_keys(targets)
    if not exact:
        fail("没有任何 e33chat.* 界面键被扫到 —— 源码根路径写错了？")
        return

    for name in sorted(targets):
        t = targets[name]
        project = t.get("project") if isinstance(t, dict) else None
        if not project or not (ROOT / project).is_dir():
            continue
        root = ROOT / "platforms" / name / "src" / "main" / "resources" / "assets" / "e33chat" / "lang"
        if not root.is_dir():
            continue
        for f in sorted(root.glob("*.json")):
            try:
                table = json.loads(f.read_text(encoding="utf-8", errors="replace"))
            except ValueError:
                continue  # check_lang_keys 已经报过 JSON 错误
            rel = f.relative_to(ROOT).as_posix()
            for key in sorted(exact - set(table)):
                fail(f"{rel} 缺界面引用的键 {key} —— 该目标上界面会显示原始键名而不是文案")
            for ns in sorted(prefix):
                if not any(k.startswith(ns) for k in table):
                    fail(f"{rel} 没有任何 {ns}* 键 —— 界面上的拼接键（{ns} + 名称）会全部落到原始键名")


# ---------------------------------------------------------------------------
# Zero-MC guard for the shared logic layer
# ---------------------------------------------------------------------------

LOGIC_DIR = "shared/src/main/java/com/niuqu/chatbubble/logic"
# Markers that must not appear in a pure-logic file. Line comments / block comments /
# string literals are stripped first, so a doc mention of a class name is fine.
MC_IMPORT_RE = re.compile(r"^\s*import\s+(static\s+)?(net\.minecraft|com\.mojang|net\.neoforged|net\.minecraftforge|net\.fabricmc)\b", re.M)
MC_QUALIFIED_RE = re.compile(r"\b(?:net\.minecraft|com\.mojang)\.[A-Za-z_]")


def _strip_literals_and_comments(text: str) -> str:
    """Remove // and /* */ comments and "..." / '...' string/char literals."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == '"' or c == "'":
            q = c
            i += 1
            while i < n and text[i] != q:
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_shared_logic_zero_mc() -> None:
    """`shared/logic/` 是纯逻辑层：一个 net.minecraft / 平台 API 的符号都不许有。
    守卫按该路径卡（§13 D1），不靠编译器 —— 共享目录其余部分允许 MC（如共用 mixin）。
    扫不到任何文件也要红：路径漂移会让这条检查悄悄变成什么都不查。"""
    base = ROOT / LOGIC_DIR
    files = sorted(base.rglob("*.java")) if base.is_dir() else []
    if not files:
        fail(f"{LOGIC_DIR} 下没有 .java —— 纯逻辑层的路径漂移了？零-MC 守卫扫了个空")
        return
    for f in files:
        text = _strip_literals_and_comments(f.read_text(encoding="utf-8", errors="replace"))
        for m in MC_IMPORT_RE.finditer(text):
            line = text[:m.start()].count("\n") + 1
            fail(f"{f.relative_to(ROOT).as_posix()}:{line} import 了 MC/平台 API —— "
                 f"shared/logic/ 必须是纯 Java（§13 D1；这些类型进不了纯逻辑单测）")
        for m in MC_QUALIFIED_RE.finditer(text):
            line = text[:m.start()].count("\n") + 1
            fail(f"{f.relative_to(ROOT).as_posix()}:{line} 直接引用了 MC 类型 —— "
                 f"shared/logic/ 必须零 net.minecraft import")


def _strip_comments(text):
    """逐行剥掉注释（块注释 + 行注释）。

    不用正则：这份文件要跨三端与 CI 跑到，少一层转义就少一类「本地能跑、CI 不能跑」的麻烦。
    近似：字符串字面量里的 // 也会被剥掉，但共享层这些类里没有那种写法。
    """
    NL = chr(10)  # 不写字面量反斜杠：这份文件要跨三端与 CI，少一层转义少一类环境差异
    out = []
    in_block = False
    for line in text.split(NL):
        if in_block:
            idx = line.find('*/')
            if idx < 0:
                continue
            line = line[idx + 2:]
            in_block = False
        start = line.find('/*')
        while start >= 0:
            idx = line.find('*/', start + 2)
            if idx < 0:
                line = line[:start]
                in_block = True
                break
            line = line[:start] + line[idx + 2:]
            start = line.find('/*', start)
        cut = line.find('//')
        if cut >= 0:
            line = line[:cut]
        out.append(line)
    return NL.join(out)


def check_server_side(cfg: dict) -> None:
    """服务端侧的类不许引用客户端类型（专用服务端上会 NoClassDefFoundError）。

    判据是源码级标记扫描（先剥注释）。patterns 是匹配式而不是清单 —— 新加的服务端类自动
    被覆盖，不必记得来登记；每个匹配式至少命中一个文件，命中为零说明匹配式坏了或布局变了，
    那也要红（否则这类检查会悄悄变成什么都不查）。
    """
    markers = cfg.get("markers", [])
    patterns = cfg.get("patterns", [])
    if not markers or not patterns:
        fail("versions/server-side.json 缺 markers 或 patterns")
        return

    seen = 0
    for pattern in patterns:
        hits = sorted(ROOT.glob(pattern))
        if not hits:
            fail(f"server-side.json 的匹配式一个文件都没命中：{pattern} —— 布局变了？")
            continue
        for f in hits:
            if not f.is_file():
                continue
            seen += 1
            text = _strip_comments(f.read_text(encoding="utf-8", errors="replace"))
            for marker in markers:
                if marker in text:
                    fail(f"{f.relative_to(ROOT).as_posix()} 是服务端侧的类，却引用了客户端类型 "
                         f"{marker} —— 专用服务端上会 NoClassDefFoundError")
    minimum = int(cfg.get("min_files", 1))
    if seen < minimum:
        fail(f"服务端侧只扫到 {seen} 个文件（声明的下限 {minimum}）—— 匹配式或布局出问题了")


def check_shared_java_level() -> None:
    """共享层必须能在**最低**的编译级别上编过，否则某个目标才炸。

    shared/ 一份源码进所有目标，而各目标的 java 级别不同（本仓是 17 与 21）。Java 17 上
    「模式匹配 switch」还只是预览特性 —— 用了它的后果不是这里报错，而是 Java 17 那个目标的
    `./gradlew build` 突然红。
    """
    pattern = re.compile(r"^\s*case\s+[A-Za-z_][\w.]*(<[^>]*>)?\s+[a-z_]\w*\s*(->|:)", re.M)
    levels = [t.get("java") for t in entries_of(load_json("versions/targets.json")).values()
              if isinstance(t, dict) and t.get("java")]
    if not levels:
        fail("versions/targets.json 里没有任何目标声明 java 级别")
        return
    lowest = min(int(v) for v in levels)
    if lowest >= 21:
        notes.append(f"所有目标的 java 级别都 ≥ {lowest}，共享层不受 17 的预览特性限制")
        return
    scanned = 0
    for f in sorted((ROOT / "shared" / "src").rglob("*.java")):
        scanned += 1
        text = _strip_comments(f.read_text(encoding="utf-8", errors="replace"))
        for m in pattern.finditer(text):
            line = text[:m.start()].count("\n") + 1
            fail(f"{f.relative_to(ROOT).as_posix()}:{line} 在共享层里用了模式匹配 switch —— "
                 f"java {lowest} 的目标编不过（那边这还是预览特性）。"
                 f"把它挪到映射层/平台层，或在共享层改用 instanceof 链")
    if scanned < 20:
        fail(f"共享层只扫到 {scanned} 个文件 —— 路径或布局变了？")


IMPORT_RE = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)\s*;", re.M)


def _collect_fqcns(*src_roots: pathlib.Path) -> set:
    """src/java 根目录下所有 .java 文件路径对应的 FQCN 集合。"""
    out = set()
    for r in src_roots:
        if not r.is_dir():
            continue
        for p in r.rglob("*.java"):
            rel = p.relative_to(r).as_posix()[:-len(".java")]
            out.add(rel.replace("/", "."))
    return out


def _resolve(imported: str, index: set) -> str | None:
    """import 目标可能是嵌套类（Outer.Inner）或静态成员（Outer.Inner.member）。
    从完整路径逐步丢尾段，直到命中某个已存在的 FQCN。"""
    parts = imported.split(".")
    while parts:
        candidate = ".".join(parts)
        if candidate in index:
            return candidate
        parts.pop()
    return None


def check_dependency_direction() -> None:
    """共享层的依赖方向：shared/ 只许引 shared/；layers/ 只许引 shared/layers，
    或【本层映射家族的全部在建目标同路径都有】的类。

    「家族全部在建目标都有」是今天的既成事实（层里十几个类引用 ChatBubbleConfig /
    ChatMessageStore 这类平台配置与存储，靠同家族各端同名才编得过；层只挂载在该家族上，
    另一个映射家族跑的是它自己的孪生副本）：把事实编码成闸，任一端改名/搬走立刻红。
    彻底解耦要等 facade 化（AtomChat 的 Platform/Net/Host 那条路线），到时把本检查
    收紧成「只许引 shared/layers」。"""
    shared_main = ROOT / "shared/src/main/java"
    shared_test = ROOT / "shared/src/test/java"
    shared_fqcns = _collect_fqcns(shared_main, shared_test)
    layers = entries_of(load_json("versions/layers.json"))
    layer_fqcns = set()
    layers_root = ROOT / "layers"
    if layers_root.is_dir():
        for axis_dir in layers_root.iterdir():
            if not axis_dir.is_dir():
                continue
            for layer in axis_dir.iterdir():
                if layer.is_dir():
                    layer_fqcns |= _collect_fqcns(layer / "src/main/java", layer / "src/test/java")

    built_platforms = []  # (target_name, mappings_family, FQCN set) 按矩阵声明顺序
    targets = entries_of(load_json("versions/targets.json"))
    for name, t in targets.items():
        if isinstance(t, dict) and (ROOT / t.get("project", "")).is_dir():
            built_platforms.append((name, t.get("mappings"), _collect_fqcns(ROOT / t["project"] / "src/main/java")))

    # 各映射家族的「家族内全部在建目标」交集 —— 映射层的类只挂载在该家族的目标上，
    # 所以家族内同名即可，不必要求另一映射家族也有（那边跑的是孪生副本）。
    family_sets: dict = {}
    for _name, fam, fqcns in built_platforms:
        family_sets.setdefault(fam, []).append(fqcns)
    family_everywhere = {fam: set.intersection(*sets) for fam, sets in family_sets.items() if sets}
    everywhere = set.intersection(*[s for _n, _f, s in built_platforms]) if built_platforms else set()

    def scan(base: pathlib.Path, allowed: set, label: str) -> None:
        if not base.is_dir():
            return
        for f in sorted(base.rglob("*.java")):
            text = f.read_text(encoding="utf-8", errors="replace")
            for m in IMPORT_RE.finditer(text):
                if not m.group(2).startswith(OWN_PREFIX):
                    continue
                if _resolve(m.group(2), allowed) is None:
                    fail(f"{f.relative_to(ROOT).as_posix()} 引用了 {m.group(2)} —— "
                         f"{label}的依赖方向不允许：{label}只许引{'共享层内部的类' if label == 'shared/' else ' shared//layers/ 或本层映射家族全部在建目标都有的类'}")

    scan(shared_main, shared_fqcns, "shared/")
    scan(shared_test, shared_fqcns, "shared/")
    if layers_root.is_dir():
        for axis_dir in layers_root.iterdir():
            if not axis_dir.is_dir():
                continue
            for layer_name in axis_dir.iterdir():
                if not layer_name.is_dir():
                    continue
                decl = layers.get(layer_name.name, {})
                fam = decl.get("mappings")
                allowed = shared_fqcns | layer_fqcns | (family_everywhere.get(fam, everywhere) if fam else everywhere)
                scan(layer_name / "src/main/java", allowed, "layers/")
                scan(layer_name / "src/test/java", allowed, "layers/")


def check_shared_dependencies(apis: dict) -> None:
    """共享层与各层里出现的第三方包，必须落在声明表内。

    这不是白名单制度，而是一张「我确实依赖了这个」的声明表：漏声明的表现是某个目标没带上
    那个库 —— 编得过、跑起来 NoClassDefFoundError。原版与加载器提供的包单列一档（不必声明），
    测试框架也单列（不进产物）。自有包（com.niuqu.chatbubble.*）由依赖方向检查管，不归这里。
    """
    declared = [k for k in apis if not k.startswith("_")]
    provided = [_root_of(line) for line in apis.get("_provided", []) if " — " in line]
    test_only = [_root_of(line) for line in apis.get("_test_only", []) if " — " in line]
    allowed = declared + provided
    if not declared and not provided:
        fail("versions/third-party-apis.json 里一个第三方包都没声明")
        return

    for base in ("shared/src", "layers"):
        basep = ROOT / base
        if not basep.is_dir():
            continue
        for f in sorted(basep.rglob("*.java")):
            is_test = "/test/" in f.as_posix()
            ok_prefixes = list(allowed) + (test_only if is_test else [])
            for m in IMPORT_RE.finditer(f.read_text(encoding="utf-8", errors="replace")):
                pkg = m.group(2)
                if pkg.startswith(("java.", "javax.", OWN_PREFIX)):
                    continue
                if any(pkg == pre or pkg.startswith(pre + ".") for pre in ok_prefixes):
                    continue
                fail(f"{f.relative_to(ROOT).as_posix()} 引用了未声明的第三方包 {pkg} —— "
                     f"把它写进 versions/third-party-apis.json（_provided 那一档是原版/加载器提供的）")


def check_aliases(aliases: dict) -> None:
    """别名必须指向真实存在的层内文件 —— 别名的价值在于它可检查，腐坏的别名会静默失效。"""
    for layer_rel, mapping in aliases.items():
        for key, value in mapping.items():
            if not (ROOT / layer_rel / key).is_file():
                fail(f"versions/mapping-aliases.json 里的 {layer_rel}/{key} 不存在 —— "
                     f"别名指向了一个没有的文件，这条对应关系已经腐坏")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix-out", help="把 CI 矩阵写成 JSON 到这个路径")
    args = parser.parse_args()

    targets_raw = load_json("versions/targets.json")
    layers_raw = load_json("versions/layers.json")
    aliases_raw = load_json("versions/mapping-aliases.json")
    paths_raw = load_json("versions/resource-paths.json")
    server_raw = load_json("versions/server-side.json")
    pinned_raw = load_json("versions/pinned-equal.json")
    targets = entries_of(targets_raw)
    layers = entries_of(layers_raw)
    aliases = {k: v for k, v in aliases_raw.items() if not k.startswith("_")}

    check_root_identity()
    check_layers(layers)
    check_aliases(aliases)
    check_resource_paths(paths_raw)
    check_ui_textures(targets)
    check_lang_keys(targets)
    check_lang_references(targets)
    check_shared_logic_zero_mc()
    check_shared_dependencies(load_json("versions/third-party-apis.json"))
    check_shared_java_level()
    check_server_side(server_raw)
    check_pinned_equal(pinned_raw)
    check_dependency_direction()
    check_targets(targets, layers, aliases)

    # 摘要（CI 日志里看这一份）
    print(f"目标 {len(targets)} 条：")
    for name, t in targets.items():
        if not isinstance(t, dict) or "project" not in t:
            continue
        mark = "发" if t.get("buildable") else "不发"
        exists = "有工程" if (ROOT / t["project"]).is_dir() else "无工程"
        print(f"  {name:18} mc={t.get('minecraft'):7} loader={t.get('loader'):9} "
              f"java={t.get('java')} gradle={t.get('gradle')} {mark}/{exists} layers={t.get('layers')}")

    if errors:
        print("\n校验未通过：", file=sys.stderr)
        for e in errors:
            print(f"  ✘ {e}", file=sys.stderr)
        return 1

    print("\n校验通过。")
    for n in notes:
        print(f"  · {n}")

    if args.matrix_out:
        # 产物名后缀在这里算好，不交给工作流里的表达式：
        # GitHub 表达式的 `a && '' || b` 因为空串是 falsy 会静默取到 b，
        # 于是"没验证"的标记会贴到正式产物上（AtomChat 踩过一次）。
        matrix = [
            {
                "target": name,
                "project": t["project"],
                "java": t["java"],
                "buildable": bool(t["buildable"]),
                "suffix": "" if t["buildable"] else "-未验证",
                # 商店版本号的组成段（发布后校验闸用）：{loader}-{mc}-{mod_version}
                "loader": t["loader"],
                "minecraft": t["minecraft"],
            }
            for name, t in targets.items()
            if isinstance(t, dict) and (ROOT / t.get("project", "")).is_dir()
        ]
        matrix.sort(key=lambda e: e["target"])
        with open(args.matrix_out, "w", encoding="utf-8") as f:
            json.dump(matrix, f, ensure_ascii=False)
        print(f"\nCI 矩阵（{len(matrix)} 个目标）已写入 {args.matrix_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
