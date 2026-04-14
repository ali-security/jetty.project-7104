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

package org.eclipse.jetty.webapp;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class WebAppDefaultServletTest
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
        IO.delete(directoryPath.toFile());
        Files.createDirectories(directoryPath);
        Path welcomeResource = directoryPath.resolve("index.html");
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
            output.write("this is confidential".getBytes(StandardCharsets.UTF_8));
        }

        // Create directory to trick resource service.
        Path hackPath = directoryPath.resolve("%57EB-INF/one.js#/");
        Files.createDirectories(hackPath);
        try (OutputStream output = Files.newOutputStream(hackPath.resolve("index.html")))
        {
            output.write("this content does not matter".getBytes(StandardCharsets.UTF_8));
        }

        Path standardHashDir = directoryPath.resolve("welcome#");
        Files.createDirectories(standardHashDir);
        try (OutputStream output = Files.newOutputStream(standardHashDir.resolve("index.html")))
        {
            output.write("standard hash dir welcome".getBytes(StandardCharsets.UTF_8));
        }

        WebAppContext context = new WebAppContext(server, directoryPath.toString(), "/");
        server.setHandler(context);
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

    private void assertResourceService(String uri, String... contains) throws Exception
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
    public void testWEBINFDirectoryBlocked() throws Exception
    {
        assertResourceService("/WEB-INF/", "HTTP/1.1 404 ");
    }

    @Test
    public void testStandardHashDirServed() throws Exception
    {
        assertResourceService("/welcome%23/", "HTTP/1.1 200 ", "standard hash dir welcome");
    }

    @Test
    public void testWelcomePageServed() throws Exception
    {
        // Normal requests for the directory are redirected to the welcome page.
        assertResourceService("/", "HTTP/1.1 200 ", "<h1>welcome page</h1>");
    }

    @Test
    public void testOtherResourceServed() throws Exception
    {
        assertResourceService("/other.html", "HTTP/1.1 200 ", "<h1>other resource</h1>");
    }

    @Test
    public void testWEBINFWithFragmentBlocked() throws Exception
    {
        // The ContextHandler will filter these ones out as WEB-INF is a protected target.
        assertResourceService("/WEB-INF/one.js#/", "HTTP/1.1 404 ");
        assertResourceService("/js/../WEB-INF/one.js#/", "HTTP/1.1 404 ");
    }

    @Test
    public void testDoubleEncodedURINotDoubleDecoded() throws Exception
    {
        // Test the URI is not double decoded by the dispatcher that serves the welcome file (we get index.html not one.js).
        assertResourceService("/%2557EB-INF/one.js%23/", "HTTP/1.1 200 ", "this content does not matter");
    }
}
