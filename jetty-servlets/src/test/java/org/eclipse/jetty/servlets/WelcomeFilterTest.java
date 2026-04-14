//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlets;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class WelcomeFilterTest
{
    private Server server;
    private LocalConnector connector;

    @Before
    public void prepareServer() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        Path directoryPath = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(directoryPath);
        Path welcomeResource = directoryPath.resolve("welcome.html");
        try (OutputStream output = Files.newOutputStream(welcomeResource))
        {
            output.write("<h1>welcome page</h1>".getBytes(StandardCharsets.UTF_8));
        }

        Path otherResource = directoryPath.resolve("other.html");
        try (OutputStream output = Files.newOutputStream(otherResource))
        {
            output.write("<h1>other resource</h1>".getBytes(StandardCharsets.UTF_8));
        }

        Path hiddenDirectory = directoryPath.resolve("WEB-INF");
        Files.createDirectories(hiddenDirectory);
        Path hiddenResource = hiddenDirectory.resolve("one.js");
        try (OutputStream output = Files.newOutputStream(hiddenResource))
        {
            output.write("CONFIDENTIAL".getBytes(StandardCharsets.UTF_8));
        }

        Path hiddenWelcome = hiddenDirectory.resolve("index.html");
        try (OutputStream output = Files.newOutputStream(hiddenWelcome))
        {
            output.write("CONFIDENTIAL".getBytes(StandardCharsets.UTF_8));
        }

        WebAppContext context = new WebAppContext(server, directoryPath.toString(), "/");
        server.setHandler(context);
        String filterPath = "/*";

        FilterHolder filterHolder = new FilterHolder(new WelcomeFilter());
        filterHolder.setInitParameter("welcome", "welcome.html");
        context.addFilter(filterHolder, filterPath, EnumSet.of(DispatcherType.REQUEST));
        server.start();

        // Verify that I can get the file programmatically, as required by the spec.
        Assert.assertNotNull(context.getServletContext().getResource("/WEB-INF/one.js"));
    }

    @After
    public void destroy() throws Exception
    {
        if (server != null)
            server.stop();
    }

    private void assertWelcomeFilter(String uri, String... contains) throws Exception
    {
        String request =
            "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponse(request);
        for (String s : contains)
        {
            assertThat(response, containsString(s));
        }
    }

    @Test
    public void testWelcomePageServed() throws Exception
    {
        // Normal requests for the directory are redirected to the welcome page.
        assertWelcomeFilter("/", "HTTP/1.1 200 ", "<h1>welcome page</h1>");
    }

    @Test
    public void testOtherResourceServed() throws Exception
    {
        // Try a normal resource (will bypass the filter).
        assertWelcomeFilter("/other.html", "HTTP/1.1 200 ", "<h1>other resource</h1>");
    }

    @Test
    public void testWEBINFFileNotServed() throws Exception
    {
        // Cannot access files in WEB-INF.
        assertWelcomeFilter("/WEB-INF/one.js", "HTTP/1.1 404 ");
    }

    @Test
    public void testWEBINFDirectoryNotServed() throws Exception
    {
        // Cannot serve welcome from WEB-INF.
        assertWelcomeFilter("/WEB-INF/", "HTTP/1.1 404 ");
    }

    @Test
    public void testWEBINFTrickNotServed() throws Exception
    {
        // Try to trick the filter into serving a protected resource.
        assertWelcomeFilter("/WEB-INF/one.js#/", "HTTP/1.1 404 ");
        assertWelcomeFilter("/js/../WEB-INF/one.js#/", "HTTP/1.1 404 ");
    }

    @Test
    public void testDoubleEncodedURINotServed() throws Exception
    {
        // Test the URI is not double decoded in the dispatcher.
        assertWelcomeFilter("/%2557EB-INF/one.js%23/", "HTTP/1.1 404 ");
    }
}
