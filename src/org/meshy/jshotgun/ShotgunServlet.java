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

import java.io.IOException;

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

	private static final long serialVersionUID = -7847588451972338615L;
	HttpServlet servlet;
	Shotgun shotgun = new Shotgun(new Target());

	@Override
	protected void service(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		try {
			shotgun.lock();
			servlet.service(request, response);
		} finally {
			shotgun.unlock();
		}
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		shotgun.setUp();
	}

	@Override
	public void destroy() {
		super.destroy();
		shotgun.destroy();
	}

	public HttpServlet loadTarget(ClassLoader classLoader)
			throws ServletException {
		String targetName = getServletConfig().getInitParameter(
				"org.meshy.jshotgun.target");
		if (targetName == null) {
			throw new ServletException(
					"org.meshy.jshotgun.target servlet parameter must be set in web.xml. eg\n"
							+ "<init-param><param-name>org.meshy.jshotgun.target</param-name>\n"
							+ "<param-value>org.example.MyServlet</param-value></init-param>");
		}
		try {
			return (HttpServlet) classLoader.loadClass(targetName)
					.newInstance();
		} catch (ClassNotFoundException | InstantiationException
				| IllegalAccessException e) {
			throw new ServletException("unable to load " + targetName, e);
		}
	}

	private class Target implements Shotgun.Target {
		@Override
		public void start(ClassLoader classLoader) {
			try {
				servlet = loadTarget(classLoader);
				servlet.init(getServletConfig());
			} catch (ServletException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void stop() {
			servlet.destroy();
			servlet = null;
		}
	}
}
