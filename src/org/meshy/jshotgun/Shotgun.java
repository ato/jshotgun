package org.meshy.jshotgun;

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
import java.util.Collections;
import java.util.HashMap;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Shotgun {

	private final ReadWriteLock lock = new ReentrantReadWriteLock();
	private final Target target;
	private SpookyClassLoader classLoader;
	private ReloaderThread reloader;
	private final Set<String> blacklist;

	public Shotgun(Target target) {
		this(target, Collections.<String>emptySet());
	}

	public Shotgun(Target target, Set<String> blacklist) {
		this.target = target;
		this.blacklist = blacklist;
	}

	public void setUp() {
		try {
			lock.writeLock().lock();
			if (classLoader == null) {
				Thread thread = Thread.currentThread();
				classLoader = new SpookyClassLoader(
						thread.getContextClassLoader(), blacklist);
				thread.setContextClassLoader(classLoader);
				target.start(classLoader);
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void tearDown() {
		try {
			lock.writeLock().lock();
			if (classLoader != null && classLoader.sawAnythingChange()) {
				target.stop();
				classLoader.close();
				classLoader = null;
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void destroy() {
		try {
			lock.writeLock().lock();
			if (classLoader != null) {
				target.stop();
				classLoader.close();
				classLoader = null;
			}
			if (reloader != null) {
				reloader.interrupt();
				reloader = null;
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void spawnReloader() {
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

	public void lock() {
		lock.readLock().lock();
		while (classLoader == null || classLoader.sawAnythingChange()) {
			lock.readLock().unlock();
			tearDown();
			setUp();
			lock.readLock().lock();
		}
		Thread.currentThread().setContextClassLoader(classLoader);
	}

	public void unlock() {
		lock.readLock().unlock();
		spawnReloader();
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
		final Set<String> blacklist;
		final Map<String, Class<?>> classes = new HashMap<>();
		WatchService watcher;
		boolean changed = false, closed = false;

		public SpookyClassLoader(ClassLoader parent, Set<String> blacklist) {
			super(parent);
			this.blacklist = blacklist;
		}

		@Override
		public synchronized Class<?> loadClass(String name)
				throws ClassNotFoundException {
			Class<?> c = classes.get(name);
			if (c != null) {
				return c;
			}
			Path path = pathForClass(name);
			if (path == null || blacklist.contains(name)) {
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

	public static interface Target {
		void start(ClassLoader classLoader);
		void stop();
	}

}