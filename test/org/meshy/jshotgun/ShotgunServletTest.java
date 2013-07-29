package org.meshy.jshotgun;

import static org.junit.Assert.*;

import org.junit.Test;
import org.meshy.jshotgun.ShotgunServlet.SpookyClassLoader;

public class ShotgunServletTest {

    @Test
    public void testClassLoader() throws Exception {
        ClassLoader parent = ShotgunServletTest.class.getClassLoader();
        try (SpookyClassLoader loader = new SpookyClassLoader(parent)) {
            Class<?> c = loader.loadClass("org.meshy.jshotgun.DummyClass");
            Class<?> c2 = loader.loadClass("org.meshy.jshotgun.DummyClass");
            Class<?> cp = parent.loadClass("org.meshy.jshotgun.DummyClass");
            assertTrue("double loading should return identical class", c == c2);
            assertTrue("parent should have its own version", c != cp);
        }
    }

}
