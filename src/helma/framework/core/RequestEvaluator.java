// RequestEvaluator.java
// Copyright (c) Hannes Walln�fer 1998-2000
 
package helma.framework.core;

import helma.objectmodel.*;
import helma.objectmodel.db.*;
import helma.framework.*;
import helma.scripting.*;
import helma.util.*;
import java.util.*;

/**
 * This class does the work for incoming requests. It holds a transactor thread
 * and an EcmaScript evaluator to get the work done. Incoming threads are
 * blocked until the request has been serviced by the evaluator, or the timeout
 * specified by the application has passed. In the latter case, the evaluator thread
 * is killed and an error message is returned.
 */

public final class RequestEvaluator implements Runnable {


    public Application app;

    protected ScriptingEnvironment scriptingEngine;

    public RequestTrans req;
    public ResponseTrans res;

    volatile Transactor rtx;

    // the object on which to invoke a function, if specified
    Object thisObject;

    // the method to be executed
    String method;

    // the user object associated with the current request
    User user;

    // arguments passed to the function
    Object[] args;

    // the object path of the request we're evaluating
    List requestPath;

    // the result of the operation
    Object result;

    // the exception thrown by the evaluator, if any.
    Exception exception;

    // the type of request to be serviced
    int reqtype;
    static final int NONE = 0;        // no request
    static final int HTTP = 1;           // via HTTP gateway
    static final int XMLRPC = 2;      // via XML-RPC
    static final int INTERNAL = 3;     // generic function call, e.g. by scheduler


    /**
     *  Create a new RequestEvaluator for this application.
     */
    public RequestEvaluator (Application app) {
	this.app = app;
    }


    public void run () {

        int txcount = 0;
        // first, set a local variable to the current transactor thread so we know
        // when it's time to quit because another thread took over.
        Transactor localrtx = (Transactor) Thread.currentThread ();

        if (scriptingEngine == null)
            scriptingEngine = helma.scripting.fesi.FesiEngineFactory.getEnvironment (app, this);

        try {
	do {

	    // long startCheck = System.currentTimeMillis ();
	    app.typemgr.checkPrototypes ();
	    // System.err.println ("Type check overhead: "+(System.currentTimeMillis ()-startCheck)+" millis");

	    // object refs to ressolve request path
	    Object root, currentElement;

	    requestPath = new ArrayList ();

	    switch (reqtype) {
	    case HTTP:
	        int tries = 0;
	        boolean done = false;
	        String error = null;
	        while (!done) {

	            currentElement = null;

	            try {

	                // used for logging
	                String txname = app.getName()+"/"+req.path;
	                // set Timer to get some profiling data
	                localrtx.timer.reset ();
	                localrtx.timer.beginEvent (requestPath+" init");
	                localrtx.begin (txname);

	                String action = null;

	                root = app.getDataRoot ();

	                HashMap globals = new HashMap ();
	                globals.put ("root", root);
	                globals.put ("user", user);
	                globals.put ("req", req);
	                globals.put ("res", res);
	                globals.put ("path", requestPath);
	                globals.put ("app", app.getAppNode());
	                if (error != null)
	                    res.error = error;
	                if (user.message != null) {
	                    // bring over the message from a redirect
	                    res.message = user.message;
	                    user.message = null;
	                }

	                try {

	                    if (error != null) {
	                        // there was an error in the previous loop, call error handler
	                        currentElement = root;
	                        requestPath.add (currentElement);
	                        String errorAction = app.props.getProperty ("error", "error");
	                        action = getAction (currentElement, errorAction);
	                        if (action == null)
	                            throw new RuntimeException (error);

	                    } else if (req.path == null || "".equals (req.path.trim ())) {
	                        currentElement = root;
	                        requestPath.add (currentElement);
	                        action = getAction (currentElement, null);
	                        if (action == null)
	                            throw new FrameworkException ("Action not found");

	                    } else {

	                        // march down request path...
	                        StringTokenizer st = new StringTokenizer (req.path, "/");
	                        int ntokens = st.countTokens ();
	                        // limit path to < 50 tokens
	                        if (ntokens > 50)
	                            throw new RuntimeException ("Path too long");
	                        String[] pathItems = new String [ntokens];
	                        for (int i=0; i<ntokens; i++)
	                              pathItems[i] = st.nextToken ();

	                        currentElement = root;
	                        requestPath.add (currentElement);

	                        for (int i=0; i<ntokens; i++) {

	                            if (currentElement == null)
	                                throw new FrameworkException ("Object not found.");

	                            // we used to do special processing for /user and /users
	                            // here but with the framework cleanup, this stuff has to be
	                            // mounted manually.

	                            // if we're at the last element of the path,
	                            // try to interpret it as action name.
	                            if (i == ntokens-1) {
	                                action = getAction (currentElement, pathItems[i]);
	                            }

	                            if (action == null) {

	                                if (pathItems[i].length () == 0)
	                                    continue;

	                                currentElement = app.getChildElement (currentElement, pathItems[i]);

	                                // add object to request path if suitable
	                                if (currentElement != null) {
	                                    // add to requestPath array
	                                    requestPath.add (currentElement);
	                                    String pt = app.getPrototypeName (currentElement);
	                                }
	                            }
	                        }

	                        if (currentElement == null)
	                            throw new FrameworkException ("Object not found.");

	                        if (action == null)
	                            action = getAction (currentElement, null);

	                        if (action == null)
	                            throw new FrameworkException ("Action not found");
	                    }

	                } catch (FrameworkException notfound) {
	                    if (error != null)
	                        // we already have an error and the error template wasn't found,
	                        // display it instead of notfound message
	                        throw new RuntimeException ();
	                    // The path could not be resolved. Check if there is a "not found" action
	                    // specified in the property file.
	                    res.status = 404;
	                    String notFoundAction = app.props.getProperty ("notFound", "notfound");
	                    currentElement = root;
	                    action = getAction (currentElement, notFoundAction);
	                    if (action == null)
	                        throw new FrameworkException (notfound.getMessage ());
	                }

	                localrtx.timer.endEvent (txname+" init");
	                /////////////////////////////////////////////////////////////////////////////
	                // end of path resolution section

	                /////////////////////////////////////////////////////////////////////////////
	                // beginning of execution section
	                try {
	                    localrtx.timer.beginEvent (txname+" execute");

	                    int actionDot = action.lastIndexOf (".");
	                    boolean isAction =  actionDot == -1;
	                    // set the req.action property, cutting off the _action suffix
	                    if (isAction)
	                        req.action = action.substring (0, action.length()-7);
	                    else
	                        req.action = action;

	                    // try calling onRequest() function on object before
	                    // calling the actual action
	                    try {
	                        scriptingEngine.invoke (currentElement, "onRequest", new Object[0], globals);
	                    } catch (RedirectException redir) {
	                        throw redir;
	                    } catch (Exception ignore) {
	                        // function is not defined or caused an exception, ignore
	                    }

	                    // do the actual action invocation
	                    if (isAction) {
	                        scriptingEngine.invoke (currentElement, action, new Object[0], globals);
	                    } else {
	                        Skin skin = app.skinmgr.getSkinInternal (app.appDir, app.getPrototype(currentElement).getName(),
	                                         action.substring (0, actionDot), action.substring (actionDot+1));
	                        if (skin != null)
	                            skin.render (this, currentElement, null);
	                        else
	                            throw new RuntimeException ("Skin "+action+" not found in "+req.path);
	                    }

	                    // check if the script set the name of a skin to render in res.skin
	                    if (res.skin != null) {
	                        int dot = res.skin.indexOf (".");
	                        Object skinObject = null;
	                        String skinName = res.skin;
	                        if (dot > -1) {
	                            String soname = res.skin.substring (0, dot);
	                            int l = requestPath.size();
	                            for (int i=l-1; i>=0; i--) {
	                                Object pathelem = requestPath.get (i);
	                                if (soname.equalsIgnoreCase (app.getPrototypeName (pathelem))) {
	                                    skinObject = pathelem;
	                                    break;
	                                }
	                            }

	                            if (skinObject == null)
	                                throw new RuntimeException ("Skin "+res.skin+" not found in path.");
	                            skinName = res.skin.substring (dot+1);
	                        }
	                        Object[] skinNameArg = new Object[1];
	                        skinNameArg[0] = skinName;
	                        scriptingEngine.invoke (skinObject, "renderSkin", skinNameArg, globals);
	                    }

	                    localrtx.timer.endEvent (txname+" execute");
	                } catch (RedirectException redirect) {
	                    // res.redirect = redirect.getMessage ();
	                    // if there is a message set, save it on the user object for the next request
	                    if (res.message != null)
	                        user.message = res.message;
	                    done = true;
	                }

	                // check if we're still the one and only or if the waiting thread has given up on us already
	                commitTransaction ();
	                done = true;

	            } catch (ConcurrencyException x) {

	                res.reset ();
	                if (++tries < 8) {
	                    // try again after waiting some period
	                    abortTransaction (true);
	                    try {
	                        // wait a bit longer with each try
	                        int base = 800 * tries;
	                        Thread.currentThread ().sleep ((long) (base + Math.random ()*base*2));
	                    } catch (Exception ignore) {}
	                    continue;
	                } else {
	                    abortTransaction (false);
	                    if (error == null) {
	                        app.errorCount += 1;
	                        // set done to false so that the error will be processed
	                        done = false;
	                        error = "Couldn't complete transaction due to heavy object traffic (tried "+tries+" times)";
	                    } else {
	                        // error in error action. use traditional minimal error message
	                        res.write ("<b>Error in application '"+app.getName()+"':</b> <br><br><pre>"+error+"</pre>");
	                        done = true;
	                    }
	                }

	            } catch (Exception x) {

	                abortTransaction (false);

	                app.logEvent ("### Exception in "+app.getName()+"/"+req.path+": "+x);
	                // Dump the profiling data to System.err
	                if (app.debug) {
	                    ((Transactor) Thread.currentThread ()).timer.dump (System.err);
	                    x.printStackTrace ();
	                }

	                // If the transactor thread has been killed by the invoker thread we don't have to
	                // bother for the error message, just quit.
	                if (localrtx != rtx)
	                    break;

	                res.reset ();
	                if (error == null) {
	                    app.errorCount += 1;
	                    // set done to false so that the error will be processed
	                    done = false;
	                    error = x.getMessage ();
	                    if (error == null || error.length() == 0)
	                        error = x.toString ();
	                } else {
	                    // error in error action. use traditional minimal error message
	                    res.write ("<b>Error in application '"+app.getName()+"':</b> <br><br><pre>"+error+"</pre>");
	                    done = true;
	                }

	            }
	        }
	        break;
	    case XMLRPC:
	        try {
	            localrtx.begin (app.getName()+":xmlrpc/"+method);

	            root = app.getDataRoot ();

	            HashMap globals = new HashMap ();
	            globals.put ("root", root);
	            globals.put ("res", res);
	            globals.put ("app", app.getAppNode());

	            currentElement = root;

	            if (method.indexOf (".") > -1) {
	                StringTokenizer st = new StringTokenizer (method, ".");
	                int cnt = st.countTokens ();
	                for (int i=1; i<cnt; i++) {
	                    String next = st.nextToken ();
	                    currentElement = app.getChildElement (currentElement, next);
	                }

	                if (currentElement == null)
	                    throw new FrameworkException ("Method name \""+method+"\" could not be resolved.");
	                method = st.nextToken ();
	            }

	            // check XML-RPC access permissions
	            String proto = app.getPrototypeName (currentElement);
	            app.checkXmlRpcAccess (proto, method);

	            result = scriptingEngine.invoke (currentElement, method, args, globals);
	            commitTransaction ();

	        } catch (Exception wrong) {

	            abortTransaction (false);

	            // If the transactor thread has been killed by the invoker thread we don't have to
	            // bother for the error message, just quit.
	            if (localrtx != rtx) {
	                return;
	            }

	            this.exception = wrong;
	        }

	        break;
	    case INTERNAL:
	        // Just a human readable descriptor of this invocation
	        String funcdesc = app.getName()+":internal/"+method;

	        // avoid going into transaction if called function doesn't exist
	        boolean functionexists = true;
	        if (thisObject == null) try {
	            functionexists = scriptingEngine.hasFunction (null, method);
			} catch (ScriptingException ignore) {}

	        if (!functionexists)
	            // global function doesn't exist, nothing to do here.
	            reqtype = NONE;
	        else try {
	            localrtx.begin (funcdesc);

	            root = app.getDataRoot ();

	            HashMap globals = new HashMap ();
	            globals.put ("root", root);
	            globals.put ("res", res);
	            globals.put ("app", app.getAppNode());

	            scriptingEngine.invoke (thisObject, method, args, globals);
	            commitTransaction ();

	        } catch (Exception wrong) {

	            abortTransaction (false);

	            // If the transactor thread has been killed by the invoker thread we don't have to
	            // bother for the error message, just quit.
	            if (localrtx != rtx) {
	                return;
	            }

	            this.exception = wrong;
	        }

	        break;

	    }

	    // make sure there is only one thread running per instance of this class
	    // if localrtx != rtx, the current thread has been aborted and there's no need to notify
	    if (localrtx != rtx) {
	        localrtx.closeConnections ();
	        return;
	    }

	    notifyAndWait ();

            }  while (localrtx == rtx);

        } finally {
            localrtx.closeConnections ();
        }
    }

    /**
     * Called by the transactor thread when it has successfully fulfilled a request.
     */
    synchronized void commitTransaction () throws Exception {
	Transactor localrtx = (Transactor) Thread.currentThread ();
	if (localrtx == rtx) {
	    reqtype = NONE;
	    localrtx.commit ();
	} else {
	    throw new TimeoutException ();
	}
    }

    synchronized void abortTransaction (boolean retry) {
	Transactor localrtx = (Transactor) Thread.currentThread ();
	if (!retry && localrtx == rtx)
	    reqtype = NONE;
	try {
	    localrtx.abort ();
	} catch (Exception ignore) {}
    }

    /**
     * Tell waiting thread that we're done, then wait for next request
     */
    synchronized void notifyAndWait () {
	Transactor localrtx = (Transactor) Thread.currentThread ();
	if (reqtype != NONE)
	    return; // is there a new request already?

	notifyAll ();
	try {
	    System.err.println ("PRE-wait "+this+" - "+reqtype);
	    // wait for request, max 10 min
	    wait (100*60*1);
	    System.err.println ("POST-wait "+this+" - "+reqtype);
	    //  if no request arrived, release ressources and thread
	    if (reqtype == NONE && rtx == localrtx) {
	        System.err.println ("      ***       Releasing engine and thread.");
	        scriptingEngine = null;
	        rtx = null;
	    }
	} catch (InterruptedException ir) {}
    }

    public synchronized ResponseTrans invoke (RequestTrans req, User user)  throws Exception {
	this.reqtype = HTTP;
	this.req = req;
	this.user = user;
	this.res = new ResponseTrans ();

	app.activeRequests.put (req, this);

	checkThread ();
	wait (app.requestTimeout);
	if (reqtype != NONE) {
	    app.logEvent ("Stopping Thread for Request "+app.getName()+"/"+req.path);
	    stopThread ();
	    res.reset ();
	    res.write ("<b>Error in application '"+app.getName()+"':</b> <br><br><pre>Request timed out.</pre>");
	}
	return res;
    }

    /**
     * This checks if the Evaluator is already executing an equal request. If so, attach to it and
     * wait for it to complete. Otherwise return null, so the application knows it has to run the request.
     */
    public synchronized ResponseTrans attachRequest (RequestTrans req) throws InterruptedException {
	if (this.req == null || res == null || !this.req.equals (req))
	    return null;
	// we already know our response object
	ResponseTrans r = res;
	if (reqtype != NONE)
	    wait (app.requestTimeout);
	return r;
    }


    public synchronized Object invokeXmlRpc (String method, Object[] args) throws Exception {
	this.reqtype = XMLRPC;
	this.user = null;
	this.method = method;
	this.args = args;
	this.res = new ResponseTrans ();
	result = null;
	exception = null;

	checkThread ();
	wait (app.requestTimeout);
	if (reqtype != NONE) {
	    stopThread ();
	}

	// reset res for garbage collection (res.data may hold reference to evaluator)
	res = null;
	if (exception != null)
	    throw (exception);
	return result;
    }

    protected Object invokeDirectFunction (Object obj, String functionName, Object[] args) throws Exception {
	return scriptingEngine.invoke (obj, functionName, args, null);
    } 

    public synchronized Object invokeFunction (Object object, String functionName, Object[] args)
		throws Exception {
	reqtype = INTERNAL;
	user = null;
	thisObject = object;
	method = functionName;
	this.args =args;
	this.res = new ResponseTrans ();
	result = null;
	exception = null;

	checkThread ();
	wait (60000l*15); // give internal call more time (15 minutes) to complete

	if (reqtype != NONE) {
	    stopThread ();
	}

	// reset res for garbage collection (res.data may hold reference to evaluator)
	res = null;
	if (exception != null)
	    throw (exception);
	return result;
    }

    public synchronized Object invokeFunction (User user, String functionName, Object[] args)
		throws Exception {
	reqtype = INTERNAL;
	this.user = user;
	thisObject = null;
	method = functionName;
	this.args = args;
	res = new ResponseTrans ();
	result = null;
	exception = null;

	checkThread ();
	wait (app.requestTimeout);

	if (reqtype != NONE) {
	    stopThread ();
	}

	// reset res for garbage collection (res.data may hold reference to evaluator)
	res = null;
	if (exception != null)
	    throw (exception);
	return result;
    }


    /**
     *  Stop this request evaluator's current thread. If currently active kill the request, otherwise just
     *  notify.
     */
    public synchronized void stopThread () {
	app.logEvent ("Stopping Thread "+rtx);
	Transactor t = rtx;
	// evaluator.thread = null;
	rtx = null;
	if (t != null) {
	    if (reqtype != NONE) {
	        reqtype = NONE;
	        t.kill ();
	        try {
	            t.abort ();
	        } catch (Exception ignore) {}
	    } else {
                     notifyAll ();
	    }
	    t.closeConnections ();
	}
    }

    private synchronized void checkThread () throws InterruptedException {

	if (app.stopped)
	    throw new ApplicationStoppedException ();

	if (rtx == null || !rtx.isAlive()) {
	    // app.logEvent ("Starting Thread");
	    rtx = new Transactor (this, app.threadgroup, app.nmgr);
	    // evaluator.thread = rtx;
	    rtx.start ();
	} else {
	    notifyAll ();
	}
    }

    /**
     *  Null out some fields, mostly for the sake of garbage collection.
     */
    public void recycle () {
        res = null;
        req = null;
        user = null;
        args = null;
        requestPath = null;
        result = null;
        exception = null;        
    }

    /**
     * Check if an action with a given name is defined for a scripted object. If it is,
     * return the action's function name. Otherwise, return null.
     */
    public String getAction (Object obj, String action) {
	if (obj == null)
	    return null;
	// check if this is a public skin, i.e. something with an extension
	// like "home.html"
	if (action != null && action.indexOf (".") > -1) {
	    int dot = action.lastIndexOf (".");
	    String extension = action.substring (dot+1);
	    String contentType = app.skinExtensions.getProperty (extension);
	    if (contentType != null) {
	        res.contentType = contentType;
	        return action;
	    } else
	        return null;
	} else {
	    String act = action == null ? "main_action" : action+"_action";
	    try {
	        if (scriptingEngine.hasFunction (obj, act))
	            return act;
	    } catch (ScriptingException x) {
	        return null;
	    }
	}
	return null;
    }



}

