package com.niuqu.chatbubble.server;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test probe for audit M11: wraps a real directory so that any attempt to
 * enumerate it ({@code Files.walk}, {@code Files.list}, {@code Files.newDirectoryStream})
 * is counted, regardless of which code performs it.
 *
 * <p>The wrapped path routes its {@link Path#getFileSystem() FileSystem} to a
 * delegating provider whose {@code newDirectoryStream} increments a counter and
 * forwards to the real provider. Because the interception is at the
 * {@link FileSystemProvider} layer, a raw {@code Files.walk(dir)} re-introduced
 * anywhere — including the old {@code dirSize()} implementation — is observed,
 * not just calls that go through a helper the production code also uses. That is
 * what makes the accompanying test a real probe instead of a fake one: put an
 * old walk back and the count moves.
 */
final class CountingFileSystem extends FileSystem {

    private final FileSystem delegate;
    private final CountingProvider provider;

    private CountingFileSystem(FileSystem delegate) {
        this.delegate = delegate;
        this.provider = new CountingProvider(delegate.provider(), this);
    }

    /** A directory wrapped for counting, plus the count read back. */
    static final class Wrapped {
        private final Path path;
        private final CountingProvider provider;

        private Wrapped(Path path, CountingProvider provider) {
            this.path = path;
            this.provider = provider;
        }

        Path path() { return path; }

        /** Directory-stream opens seen so far (each {@code Files.walk/list} opens at least one). */
        int directoryStreamCount() { return provider.count.get(); }
    }

    static Wrapped wrap(Path real) {
        CountingFileSystem fs = new CountingFileSystem(real.getFileSystem());
        return new Wrapped(new CountingPath(real, fs), fs.provider);
    }

    /** The real path behind a wrapped one, or the path itself. */
    static Path unwrap(Path p) {
        return p instanceof CountingPath cp ? cp.delegate : p;
    }

    @Override public FileSystemProvider provider() { return provider; }
    @Override public void close() { /* shared default filesystem: never close */ }
    @Override public boolean isOpen() { return delegate.isOpen(); }
    @Override public boolean isReadOnly() { return delegate.isReadOnly(); }
    @Override public String getSeparator() { return delegate.getSeparator(); }
    @Override public Iterable<Path> getRootDirectories() { return delegate.getRootDirectories(); }
    @Override public Iterable<FileStore> getFileStores() { return delegate.getFileStores(); }
    @Override public Set<String> supportedFileAttributeViews() { return delegate.supportedFileAttributeViews(); }
    @Override public Path getPath(String first, String... more) { return delegate.getPath(first, more); }
    @Override public PathMatcher getPathMatcher(String syntaxAndPattern) { return delegate.getPathMatcher(syntaxAndPattern); }
    @Override public UserPrincipalLookupService getUserPrincipalLookupService() { return delegate.getUserPrincipalLookupService(); }
    @Override public WatchService newWatchService() throws IOException { return delegate.newWatchService(); }

    /** Counts directory enumerations, delegating everything else to the real provider. */
    private static final class CountingProvider extends FileSystemProvider {
        private final FileSystemProvider delegate;
        private final CountingFileSystem fs;
        private final AtomicInteger count = new AtomicInteger();

        private CountingProvider(FileSystemProvider delegate, CountingFileSystem fs) {
            this.delegate = delegate;
            this.fs = fs;
        }

        @Override public String getScheme() { return delegate.getScheme(); }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }
        @Override public FileSystem newFileSystem(Path path, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }
        @Override public FileSystem getFileSystem(URI uri) { return delegate.getFileSystem(uri); }
        @Override public Path getPath(URI uri) { return delegate.getPath(uri); }

        @Override public DirectoryStream<Path> newDirectoryStream(
                Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            count.incrementAndGet();
            return delegate.newDirectoryStream(unwrap(dir), filter);
        }

        @Override public java.nio.channels.SeekableByteChannel newByteChannel(
                Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            return delegate.newByteChannel(unwrap(path), options, attrs);
        }
        @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            delegate.createDirectory(unwrap(dir), attrs);
        }
        @Override public void delete(Path path) throws IOException { delegate.delete(unwrap(path)); }
        @Override public void copy(Path source, Path target, CopyOption... options) throws IOException {
            delegate.copy(unwrap(source), unwrap(target), options);
        }
        @Override public void move(Path source, Path target, CopyOption... options) throws IOException {
            delegate.move(unwrap(source), unwrap(target), options);
        }
        @Override public boolean isSameFile(Path path, Path path2) throws IOException {
            return delegate.isSameFile(unwrap(path), unwrap(path2));
        }
        @Override public boolean isHidden(Path path) throws IOException { return delegate.isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException { return delegate.getFileStore(unwrap(path)); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException {
            delegate.checkAccess(unwrap(path), modes);
        }
        @Override public <V extends FileAttributeView> V getFileAttributeView(
                Path path, Class<V> type, LinkOption... options) {
            return delegate.getFileAttributeView(unwrap(path), type, options);
        }
        @Override public <A extends BasicFileAttributes> A readAttributes(
                Path path, Class<A> type, LinkOption... options) throws IOException {
            return delegate.readAttributes(unwrap(path), type, options);
        }
        @Override public Map<String, Object> readAttributes(
                Path path, String attributes, LinkOption... options) throws IOException {
            return delegate.readAttributes(unwrap(path), attributes, options);
        }
        @Override public void setAttribute(
                Path path, String attribute, Object value, LinkOption... options) throws IOException {
            delegate.setAttribute(unwrap(path), attribute, value, options);
        }
        @Override public Path readSymbolicLink(Path link) throws IOException {
            return delegate.readSymbolicLink(unwrap(link));
        }
    }

    /** A {@link Path} that reports the counting filesystem; children are real paths. */
    private static final class CountingPath implements Path {
        private final Path delegate;
        private final CountingFileSystem fs;

        private CountingPath(Path delegate, CountingFileSystem fs) {
            this.delegate = delegate;
            this.fs = fs;
        }

        @Override public FileSystem getFileSystem() { return fs; }
        @Override public boolean isAbsolute() { return delegate.isAbsolute(); }
        @Override public Path getRoot() { return delegate.getRoot(); }
        @Override public Path getFileName() { return delegate.getFileName(); }
        @Override public Path getParent() { return delegate.getParent(); }
        @Override public int getNameCount() { return delegate.getNameCount(); }
        @Override public Path getName(int index) { return delegate.getName(index); }
        @Override public Path subpath(int beginIndex, int endIndex) { return delegate.subpath(beginIndex, endIndex); }
        @Override public boolean startsWith(Path other) { return delegate.startsWith(unwrap(other)); }
        @Override public boolean startsWith(String other) { return delegate.startsWith(other); }
        @Override public boolean endsWith(Path other) { return delegate.endsWith(unwrap(other)); }
        @Override public boolean endsWith(String other) { return delegate.endsWith(other); }
        @Override public Path normalize() { return delegate.normalize(); }
        @Override public Path resolve(Path other) { return delegate.resolve(unwrap(other)); }
        @Override public Path resolve(String other) { return delegate.resolve(other); }
        @Override public Path resolveSibling(Path other) { return delegate.resolveSibling(unwrap(other)); }
        @Override public Path resolveSibling(String other) { return delegate.resolveSibling(other); }
        @Override public Path relativize(Path other) { return delegate.relativize(unwrap(other)); }
        @Override public URI toUri() { return delegate.toUri(); }
        @Override public Path toAbsolutePath() { return delegate.toAbsolutePath(); }
        @Override public Path toRealPath(LinkOption... options) throws IOException { return delegate.toRealPath(options); }
        @Override public File toFile() { return delegate.toFile(); }
        @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                                           WatchEvent.Modifier... modifiers) throws IOException {
            return delegate.register(watcher, events, modifiers);
        }
        @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
            return delegate.register(watcher, events);
        }
        @Override public Iterator<Path> iterator() { return delegate.iterator(); }
        @Override public int compareTo(Path other) { return delegate.compareTo(unwrap(other)); }
        @Override public boolean equals(Object other) {
            return delegate.equals(other instanceof CountingPath cp ? cp.delegate : other);
        }
        @Override public int hashCode() { return delegate.hashCode(); }
        @Override public String toString() { return delegate.toString(); }
        @Override public Spliterator<Path> spliterator() { return delegate.spliterator(); }
    }
}
