/*
 * Helma License Notice
 *
 * The contents of this file are subject to the Helma License
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. A copy of the License is available at
 * http://adele.helma.org/download/helma/license.txt
 *
 * Copyright 1998-2003 Helma Software. All Rights Reserved.
 *
 * $RCSfile$
 * $Author$
 * $Revision$
 * $Date$
 */

package helma.servlet;

import helma.framework.repository.Repository;
import helma.framework.core.Application;
import helma.framework.repository.FileRepository;
import helma.main.ServerConfig;
import helma.main.Server;

import java.io.*;
import javax.servlet.*;
import java.util.*;

/**
 *  Standalone servlet client that runs a Helma application all by itself
 *  in embedded mode without relying on a central instance of helma.main.Server
 *  to start and manage the application.
 *
 *  StandaloneServletClient takes the following init parameters:
 *     <ul>
 *       <li> application - the application name </li>
 *       <li> appdir - the path of the application home directory </li>
 *       <li> dbdir - the path of the embedded XML data store </li>
 *     </ul>
 */
public final class StandaloneServletClient extends AbstractServletClient {
    private static final long serialVersionUID = 6515895361950250466L;

    private Application app = null;
    private String appName;
    private String appDir;
    private String dbDir;
    private String hopDir;
    private Repository[] repositories;

    /**
     *
     *
     * @param init ...
     *
     * @throws ServletException ...
     */
    @Override
    public void init(ServletConfig init) throws ServletException {
        super.init(init);

        hopDir = init.getInitParameter("hopdir"); //$NON-NLS-1$

        if (hopDir == null) {
            // assume helmaDir to be current directory
            hopDir = "."; //$NON-NLS-1$
        }

        appName = init.getInitParameter("application"); //$NON-NLS-1$

        if ((appName == null) || (appName.trim().length() == 0)) {
            throw new ServletException(Messages.getString("StandaloneServletClient.0")); //$NON-NLS-1$
        }

        appDir = init.getInitParameter("appdir"); //$NON-NLS-1$

        dbDir = init.getInitParameter("dbdir"); //$NON-NLS-1$

        if ((dbDir == null) || (dbDir.trim().length() == 0)) {
            throw new ServletException(Messages.getString("StandaloneServletClient.1")); //$NON-NLS-1$
        }

        Class[] parameters = { String.class };
        ArrayList repositoryList = new ArrayList();

        for (int i = 0; true; i++) {
            String repositoryArgs = init.getInitParameter("repository." + i); //$NON-NLS-1$
            if (repositoryArgs != null) {
                // lookup repository implementation
                String repositoryImpl = init.getInitParameter("repository." + i + //$NON-NLS-1$
                        ".implementation"); //$NON-NLS-1$
                if (repositoryImpl == null) {
                    // implementation not set manually, have to guess it
                    if (repositoryArgs.endsWith(".zip")) { //$NON-NLS-1$
                        repositoryImpl = "helma.framework.repository.ZipRepository"; //$NON-NLS-1$
                    } else if (repositoryArgs.endsWith(".js")) { //$NON-NLS-1$
                        repositoryImpl = "helma.framework.repository.SingleFileRepository"; //$NON-NLS-1$
                    } else {
                        repositoryImpl = "helma.framework.repository.FileRepository"; //$NON-NLS-1$
                    }
                }
        
                try {
                    Repository newRepository = (Repository) Class.forName(repositoryImpl)
                        .getConstructor(parameters)
                        .newInstance(new Object[] {repositoryArgs});
                    repositoryList.add(newRepository);
                    log(Messages.getString("StandaloneServletClient.2") + repositoryArgs); //$NON-NLS-1$
                } catch (Exception ex) {
                    log(Messages.getString("StandaloneServletClient.3") + repositoryArgs + Messages.getString("StandaloneServletClient.4") + //$NON-NLS-1$ //$NON-NLS-2$
                        Messages.getString("StandaloneServletClient.5"), ex); //$NON-NLS-1$
                }
            } else {
                // we always scan repositories 0-9, beyond that only if defined
                if (i > 9) {
                    break;
                }
            }
        }
        
        // add app dir
        FileRepository appRep = new FileRepository(appDir);
        log(Messages.getString("StandaloneServletClient.6") + appDir); //$NON-NLS-1$
        if (!repositoryList.contains(appRep)) {
            repositoryList.add(appRep);
        }

        repositories = new Repository[repositoryList.size()];
        repositories = (Repository[]) repositoryList.toArray(repositories);

    }

    /**
     * Returns the {@link helma.framework.core.Application Applicaton}
     * instance the servlet is talking to.
     *
     * @return this servlet's application instance
     */
    @Override
    public Application getApplication() {
        if (app == null) {
            createApp();
        }

        return app;
    }

    /**
     * Create the application. Since we are synchronized only here, we
     * do another check if the app already exists and immediately return if it does.
     */
    protected synchronized void createApp() {

        if (app != null) {
            return;
        }

        try {
            File dbHome = new File(dbDir);
            File appHome = new File(appDir);
            File hopHome = new File(hopDir);

            ServerConfig config = new ServerConfig();
            config.setHomeDir(hopHome);
            Server server = new Server(config);
            server.init();

            app = new Application(appName, server, repositories, appHome, dbHome);
            app.init();
            app.start();
        } catch (Exception x) {
            log(Messages.getString("StandaloneServletClient.7") + appName + Messages.getString("StandaloneServletClient.8") + x); //$NON-NLS-1$ //$NON-NLS-2$
            x.printStackTrace();
        }
    }

    /**
     * The servlet is being destroyed. Close and release the application if
     * it does exist.
     */
    @Override
    public void destroy() {
        if (app != null) {
            try {
                app.stop();
            } catch (Exception x) {
                log(Messages.getString("StandaloneServletClient.9") + app.getName() + Messages.getString("StandaloneServletClient.10")); //$NON-NLS-1$ //$NON-NLS-2$
                x.printStackTrace();
            }
        }

        app = null;
    }
}
