/*
 * Copyright (c) 2010-2011 Engine Yard, Inc.
 * Copyright (c) 2007-2009 Sun Microsystems, Inc.
 * This source code is available under the MIT license.
 * See the file LICENSE.txt for details.
 */

package org.jruby.rack;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.Servlet;

import org.jruby.CompatVersion;
import org.jruby.Ruby;
import org.jruby.RubyInstanceConfig;
import org.jruby.embed.osgi.OSGiScriptingContainer;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.JavaUtil;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * 
 * @author nicksieger
 */
public class OSGiRackApplicationFactory implements RackApplicationFactory {
	private String rackupScript, rackupLocation;
	private Bundle bundle;
	private Servlet servlet;
	private RackContext rackContext;
	private RubyInstanceConfig defaultConfig;
	private RackApplication errorApplication;

	public OSGiRackApplicationFactory(Bundle bundle, Servlet servlet) {
		this.bundle = bundle;
		this.servlet = servlet;
	}

	public void init(RackContext rackContext) {
		this.rackContext = rackContext;
		this.rackupScript = findRackupScript();
		this.defaultConfig = createDefaultConfig();
		rackContext.log(defaultConfig.getVersionString());
	}

	public RackApplication newApplication() throws RackInitializationException {
		return createApplication(new ApplicationObjectFactory() {
			public IRubyObject create(OSGiScriptingContainer runtime) {
				return createApplicationObject(runtime);
			}
		});
	}

	public RackApplication getApplication() throws RackInitializationException {
		RackApplication app = newApplication();
		app.init();
		return app;
	}

	public void finishedWithApplication(RackApplication app) {
		app.destroy();
	}

	public synchronized RackApplication getErrorApplication() {
		if (errorApplication == null) {
			errorApplication = newErrorApplication();
		}
		return errorApplication;
	}

	public void destroy() {
		if (errorApplication != null) {
			errorApplication.destroy();
			errorApplication = null;
		}
	}

	public RackContext getRackContext() {
		return rackContext;
	}

	public IRubyObject createApplicationObject(OSGiScriptingContainer container) {
		if (rackupScript == null) {
			rackContext
					.log("WARNING: no rackup script found. Starting empty Rack application.");
			rackupScript = "";
		}
		return createRackServletWrapper(container, rackupScript);
	}

	public IRubyObject createErrorApplicationObject(
			OSGiScriptingContainer container) {
		return createRackServletWrapper(container,
				"run JRuby::Rack::ErrorsApp.new");
	}

	public RackApplication newErrorApplication() {
		try {
			RackApplication app = createApplication(new ApplicationObjectFactory() {
				public IRubyObject create(OSGiScriptingContainer container) {
					return createErrorApplicationObject(container);
				}
			});
			app.init();
			return app;
		} catch (final Exception e) {
			rackContext.log(
					"Warning: error application could not be initialized", e);
			return new RackApplication() {
				public void init() throws RackInitializationException {
				}

				public RackResponse call(RackEnvironment env) {
					return new RackResponse() {
						public int getStatus() {
							return 500;
						}

						@SuppressWarnings("rawtypes")
						public Map getHeaders() {
							return Collections.EMPTY_MAP;
						}

						public String getBody() {
							return "Application initialization failed: "
									+ e.getMessage();
						}

						public void respond(RackResponseEnvironment response) {
							try {
								response.defaultRespond(this);
							} catch (IOException ex) {
								rackContext.log("Error writing body", ex);
							}
						}
					};
				}

				public void destroy() {
				}

				public Ruby getRuntime() {
					throw new UnsupportedOperationException("not supported");
				}
			};
		}
	}

	protected IRubyObject createRackServletWrapper(
			OSGiScriptingContainer runtime, String rackup) {
		return (IRubyObject) runtime.getProvider().getRuntime()
				.executeScript("load 'jruby/rack/boot/rack.rb';"
						+ "Rack::Handler::Servlet.new(Rack::Builder.new {( "
						+ rackup + "\n )}.to_app)",
						rackupLocation);
	}

	private interface ApplicationObjectFactory {
		IRubyObject create(OSGiScriptingContainer runtime);
	}

	private RubyInstanceConfig createDefaultConfig() {
		setupJRubyManagement();
		RubyInstanceConfig config = new RubyInstanceConfig();
		if (rackContext.getConfig().getCompatVersion() != null) {
			config.setCompatVersion(rackContext.getConfig().getCompatVersion());
		}

		try { // try to set jruby home to jar file path
			URL resource = RubyInstanceConfig.class
					.getResource("/META-INF/jruby.home");
			System.out.println("jruby.home RESOURCE: " + resource);
			if (resource.getProtocol().equals("jar")) {
				String home;
				try { // http://weblogs.java.net/blog/2007/04/25/how-convert-javaneturl-javaiofile
					home = resource.toURI().getSchemeSpecificPart();
				} catch (URISyntaxException urise) {
					home = resource.getPath();
				}

				// Trim trailing slash. It confuses OSGi containers...
				if (home.endsWith("/")) {
					home = home.substring(0, home.length() - 1);
				}
				config.setJRubyHome(home);
			}
		} catch (Exception e) {
		}

		// Process arguments, namely any that might be in RUBYOPT
		config.processArguments(new String[0]);
		return config;
	}

	private void initializeRuntime(OSGiScriptingContainer container)
			throws RackInitializationException {
		try {
			Ruby runtime = container.getProvider().getRuntime();
			IRubyObject context = JavaUtil.convertJavaToRuby(runtime,
					rackContext);
			runtime.getGlobalVariables().set("$servlet_context", context);
			runtime.getGlobalVariables().set("$bundle", JavaUtil.convertJavaToRuby(runtime, this.bundle));
			runtime.getGlobalVariables().set("$classloader",
					JavaUtil.convertJavaToRuby(runtime, this.servlet.getClass().getClassLoader()));
			if (rackContext.getConfig().isIgnoreEnvironment()) {
				runtime.evalScriptlet("ENV.clear");
			}
			runtime.evalScriptlet("require 'rack/handler/servlet'");
		} catch (RaiseException re) {
			throw new RackInitializationException(re);
		}
	}

	/** This method is only public for unit tests */
	public OSGiScriptingContainer newRuntime()
			throws RackInitializationException {
		OSGiScriptingContainer container = new OSGiScriptingContainer(
				FrameworkUtil.getBundle(this.getClass()));
		container.setCompatVersion(CompatVersion.RUBY1_8);
		initializeRuntime(container);
		return container;
	}

	private RackApplication createApplication(
			final ApplicationObjectFactory appfact)
			throws RackInitializationException {
		try {
			final OSGiScriptingContainer container = newRuntime();
			return new DefaultRackApplication() {
				@Override
				public void init() throws RackInitializationException {
					try {
						setApplication(appfact.create(container));
					} catch (RaiseException re) {
						captureMessage(re);
						throw new RackInitializationException(re);
					}
				}

				@Override
				public void destroy() {
					container.terminate();
				}
			};
		} catch (RackInitializationException rie) {
			throw rie;
		} catch (RaiseException re) {
			throw new RackInitializationException(re);
		}
	}

	private void captureMessage(RaiseException rex) {
		try {
			IRubyObject rubyException = rex.getException();
			ThreadContext context = rubyException.getRuntime()
					.getCurrentContext();
			rubyException.callMethod(context, "capture");
			rubyException.callMethod(context, "store");
		} catch (Exception e) {
			// won't be able to capture anything
		}
	}

	private String findConfigRuPathInSubDirectories(String path, int level) {
		@SuppressWarnings("rawtypes")
		Set entries = rackContext.getResourcePaths(path);
		if (entries != null) {
			if (entries.contains(path + "config.ru")) {
				return path + "config.ru";
			}

			if (level > 0) {
				level--;
				for (@SuppressWarnings("rawtypes")
				Iterator i = entries.iterator(); i.hasNext();) {
					String subpath = (String) i.next();
					if (subpath.endsWith("/")) {
						subpath = findConfigRuPathInSubDirectories(subpath,
								level);
						if (subpath != null) {
							return subpath;
						}
					}
				}
			}
		}
		return null;
	}

	private static final Pattern CODING = Pattern.compile("coding:\\s*(\\S+)");

	private String inputStreamToString(InputStream stream) {
		if (stream == null) {
			return null;
		}

		try {
			StringBuilder str = new StringBuilder();
			int c = stream.read();
			Reader reader;
			String coding = "UTF-8";
			if (c == '#') { // look for a coding: pragma
				str.append((char) c);
				while ((c = stream.read()) != -1 && c != 10) {
					str.append((char) c);
				}
				Matcher m = CODING.matcher(str.toString());
				if (m.find()) {
					coding = m.group(1);
				}
			}

			str.append((char) c);
			reader = new InputStreamReader(stream, coding);

			while ((c = reader.read()) != -1) {
				str.append((char) c);
			}

			return str.toString();
		} catch (Exception e) {
			rackContext.log("Error reading rackup input", e);
			return null;
		}
	}

	private String findRackupScript() {
		rackupLocation = "<web.xml>";

		String rackup = rackContext.getConfig().getRackup();
		if (rackup != null) {
			return rackup;
		}

		rackup = rackContext.getConfig().getRackupPath();

		if (rackup == null) {
			rackup = findConfigRuPathInSubDirectories("/WEB-INF/", 1);
		}

		if (rackup == null) { // google-appengine gem prefers it at /config.ru
			rackup = findConfigRuPathInSubDirectories("/", 0);
		}

		if (rackup != null) {
			rackupLocation = rackContext.getRealPath(rackup);
			rackup = inputStreamToString(rackContext
					.getResourceAsStream(rackup));
		}

		return rackup;
	}

	private void setupJRubyManagement() {
		if (!"false".equalsIgnoreCase(System
				.getProperty("jruby.management.enabled"))) {
			System.setProperty("jruby.management.enabled", "true");
		}
	}

	/** Used only by unit tests */
	public void setErrorApplication(RackApplication app) {
		this.errorApplication = app;
	}

	/** Used only by unit tests */
	public String getRackupScript() {
		return rackupScript;
	}
}
