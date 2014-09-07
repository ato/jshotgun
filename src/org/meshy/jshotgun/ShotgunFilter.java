package org.meshy.jshotgun;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * A nimble code reloader for Java servlet filters.
 *
 * Before each request it checks if any class files were changed and if so
 * reloads them and reinitializes the wrapped filter. Classes loaded from jars
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
public class ShotgunFilter implements Filter {
	Shotgun shotgun = new Shotgun(new Target());
    Filter filter;
    FilterConfig filterConfig;
    
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		this.filterConfig = filterConfig;
		shotgun.setUp();
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		try {
			shotgun.lock();
			filter.doFilter(request, response, chain);
		} finally {
			shotgun.unlock();
		}
	}

	@Override
	public void destroy() {
		shotgun.destroy();
	}
	
	private Filter loadTarget(ClassLoader classLoader)
			throws ServletException {
		String targetName = filterConfig.getInitParameter("org.meshy.jshotgun.target");
		if (targetName == null) {
			throw new ServletException(
					"org.meshy.jshotgun.target servlet parameter must be set in web.xml. eg\n"
							+ "<init-param><param-name>org.meshy.jshotgun.target</param-name>\n"
							+ "<param-value>org.example.MyServlet</param-value></init-param>");
		}
		try {
			return (Filter) classLoader.loadClass(targetName).newInstance();
		} catch (ClassNotFoundException | InstantiationException
				| IllegalAccessException e) {
			throw new ServletException("unable to load " + targetName, e);
		}
	}
	
	private class Target implements Shotgun.Target {
		@Override
		public void start(ClassLoader classLoader) {
			try {
				filter = loadTarget(classLoader);
				filter.init(filterConfig);
			} catch (ServletException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void stop() {
			filter.destroy();
			filter = null;
		}
	}
}
