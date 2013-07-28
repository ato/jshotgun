JShotgun
========

JShotgun ([view source]) is an auto reloading Java servlet. It can be used as
a simple alternative to the complex reloading logic that is provided by some web
frameworks.

JShotgun loads your application in a temporary class loader. It then watches any
classes loaded from local files. If a class file is changed on disk it reloads
the entire application. Classes loaded from jar files are excluded so only
application code is reloaded. This also means that JShotgun automatically
becomes a noop in most production environments.

You will need a method of automating the compilation. That could be anything
from Eclipse's "Project Menu -> Build Automatically" option to a simple shell
script:

    while true; do inotifywait *.java; javac *.java; done

Requires Java 7 and is fastest on operating systems with native WatchService
support (Linux and Solaris). Reloads immediately in the background so most of
the time there's no noticeable delay at all.

Concurrency safe: waits for any outstanding requests to be completed before
reloading.

Usage
-----

Configure it in your web.xml file, wrapping another HttpServlet class.
Here's an example of using it with [Resteasy]:

    <servlet>
      <servlet-name>ExampleServlet</servlet-name>
      <servlet-class>org.meshy.jshotgun.ShotgunServlet</servlet-class>
      <init-param>
        <param-name>org.meshy.jshotgun.target</param-name>
        <param-value>org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher</param-value>
      </init-param>
    </servlet>
    <servlet-mapping>
      <servlet-name>ExampleServlet</servlet-name>
      <url-pattern>/*</url-pattern>
    </servlet-mapping>

Getting it
----------

JShotgun is available from the [Clojars] Maven repository ([raw jar files]).

Some day I'll get around to pushing it to Central. For now you'll need to add
the Clojars repository:

    <repository>
     <id>clojars.org</id>
     <url>http://clojars.org/repo</url>
    </repository>

Add this dependency to your pom.xml:

    <dependency>
      <groupId>org.meshy</groupId>
      <artifactId>jshotgun</artifactId>
      <version>1.0.0</version>
    </dependency>

See also
--------

* [Shotgun] - the Ruby library that inspired JShotgun
* [Spring Loaded] - a more sophisticated reloader that uses a JVM agent

License
-------

The MIT License (MIT)
Copyright (c) 2013 Alex Osborne

[Resteasy]: https://www.jboss.org/resteasy
[Clojars]: https://clojars.org/org.meshy/jshotgun
[raw jar files]: https://clojars.org/repo/org/meshy/jshotgun/
[Shotgun]: https://github.com/rtomayko/shotgun
[Spring Loaded]: https://github.com/SpringSource/spring-loaded
[view source]: https://github.com/ato/jshotgun/blob/master/src/org/meshy/jshotgun/ShotgunServlet.java