package org.meshy.jshotgun;

/*
 * jshotgun - nimble code reloading for Java servlets
 * https://github.com/ato/jshotgun
 *
 * Copyright (c) 2013 Alex Osborne
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchService;
import java.security.SecureClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A nimble code reloader for Java servlets.
 *
 * Before each request it checks if any class files were changed and if so
 * reloads them and reinitializes the wrapped servlet. Classes loaded from jars
 * are excluded so this class automatically becomes a noop in most production
 * deployments.
 *
 * Starts reloading immediately in the background so most of the time there's no
 * delay at all.
 *
 * You'll need away of automating the compilation but that could be anything
 * from Eclipse's "build automatically" option to a shell script:
 *
 * <pre>
 *     while true; do inotifywait *.java; make; done
 * </pre>
 *
 * Concurrency safe: waits for any outstanding requests to be completed before
 * reloading.
 *
 * @author Alex Osborne
 * @see <a href="https://github.com/ato/jshotgun">jshotgun</a>
 */
public class ShotgunServlet extends HttpServlet {

    private static final long serialVersionUID = -7847588451972338616L;
    final ReadWriteLock lock = new ReentrantReadWriteLock();
    SpookyClassLoader classLoader;
    HttpServlet servlet;
    ReloaderThread reloader;

    @Override
    protected void service(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        try {
            lock.readLock().lock();
            while (servlet == null || classLoader.sawAnythingChange()) {
                lock.readLock().unlock();
                tearDown();
                setUp();
                lock.readLock().lock();
            }
            Thread.currentThread().setContextClassLoader(classLoader);
            servlet.service(request, response);
        } finally {
            lock.readLock().unlock();
        }
        spawnReloader();
    }

    void setUp() throws ServletException {
        try {
            lock.writeLock().lock();
            if (servlet == null) {
                Thread thread = Thread.currentThread();
                classLoader = new SpookyClassLoader(
                        thread.getContextClassLoader());
                thread.setContextClassLoader(classLoader);
                servlet = loadTarget(classLoader);
                servlet.init(getServletConfig());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    void tearDown() {
        try {
            lock.writeLock().lock();
            if (servlet != null && classLoader.sawAnythingChange()) {
                servlet.destroy();
                servlet = null;
                classLoader.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    void spawnReloader() {
        if (reloader == null && classLoader.isWatchingAnything()) {
            try {
                lock.writeLock().lock();
                if (reloader == null && classLoader.isWatchingAnything()) {
                    reloader = new ReloaderThread();
                    reloader.start();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        setUp();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            lock.writeLock().lock();
            if (servlet != null) {
                classLoader.close();
                servlet.destroy();
                servlet = null;
            }
            if (reloader != null) {
                reloader.interrupt();
                reloader = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public HttpServlet loadTarget(ClassLoader classLoader)
            throws ServletException {
        String target = getServletConfig().getInitParameter(
                "org.meshy.jshotgun.target");
        if (target == null) {
            throw new ServletException(
                    "org.meshy.jshotgun.target servlet parameter must be set in web.xml. eg\n"
                            + "<init-param><param-name>org.meshy.jshotgun.target</param-name>\n"
                            + "<param-value>org.example.MyServlet</param-value></init-param>");
        }
        try {
            return (HttpServlet) classLoader.loadClass(target).newInstance();
        } catch (ClassNotFoundException | InstantiationException
                | IllegalAccessException e) {
            throw new ServletException("unable to load " + target, e);
        }
    }

    /**
     * Watches for changes in the background and reloads as soon as it sees any.
     */
    class ReloaderThread extends Thread {

        ReloaderThread() {
            setName(ReloaderThread.class.getName());
        }

        @Override
        public void run() {
            try {
                for (;;) {
                    setUp();
                    if (classLoader.waitForChange()) {
                        tearDown();
                    }
                }
            } catch (ServletException e) {
                throw new RuntimeException("reloading failed", e);
            } catch (InterruptedException e) {
                // we're done.
            }
            reloader = null;
        }

    }

    /**
     * This ClassLoader loads local files without telling its parents. Classes
     * loaded from jars or anywhere else are just delegated as normal.
     *
     * The directories the classes were loaded from are watched for changes.
     */
    static class SpookyClassLoader extends SecureClassLoader implements
            AutoCloseable {
        final Map<String, Class<?>> classes = new HashMap<>();
        WatchService watcher;
        boolean changed = false, closed = false;

        public SpookyClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public synchronized Class<?> loadClass(String name) throws ClassNotFoundException {
            Class<?> c = classes.get(name);
            if (c != null) {
                return c;
            }
            Path path = pathForClass(name);
            if (path == null) {
                return super.loadClass(name);
            } else {
                try {
                    return loadClass(name, path);
                } catch (Exception e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }

        private Class<?> loadClass(String name, Path path) throws Exception {
            if (!closed) {
                watch(path.getParent());
            }
            byte[] b = Files.readAllBytes(path);
            Class<?> c = defineClass(name, b, 0, b.length);
            classes.put(name, c);
            return c;
        }

        private void watch(Path dir) throws IOException {
            try {
                // delay creating the WatchService until we actually need it as
                // it starts a background polling thread
                if (watcher == null) {
                    watcher = FileSystems.getDefault().newWatchService();
                }
                dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            } catch (NoSuchFileException e) {
                // directory disappeared? that's ok.
            }
        }

        Path pathForClass(String name) {
            URL url = getResource(name.replace('.', '/').concat(".class"));
            if (url != null && "file".equals(url.getProtocol())) {
                try {
                    return Paths.get(url.toURI());
                } catch (URISyntaxException e) {
                    return null;
                }
            } else {
                return null;
            }
        }

        boolean sawAnythingChange() {
            if (!closed && watcher != null && watcher.poll() != null) {
                changed = true;
                close();
            }
            return changed;
        }

        boolean waitForChange() throws InterruptedException {
            try {
                if (!closed && watcher != null && watcher.take() != null) {
                    changed = true;
                    close();
                }
            } catch (ClosedWatchServiceException e) {
                // that's ok.
            }
            return changed;
        }

        boolean isWatchingAnything() {
            return watcher != null;
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (watcher != null) {
                try {
                    watcher.close();
                    watcher = null;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
