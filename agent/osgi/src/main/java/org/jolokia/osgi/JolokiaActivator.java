package org.jolokia.osgi;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;

import javax.servlet.ServletException;

import org.jolokia.config.ConfigKey;
import org.jolokia.osgi.security.*;
import org.jolokia.osgi.servlet.JolokiaContext;
import org.jolokia.osgi.servlet.JolokiaServlet;
import org.jolokia.restrictor.Restrictor;
import org.jolokia.util.NetworkUtil;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.http.*;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import static org.jolokia.config.ConfigKey.*;

/*
 * Copyright 2009-2013 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/**
 * OSGi Activator for the Jolokia Agent
 *
 * @author roland
 * @since Dec 27, 2009
 */
public class JolokiaActivator implements BundleActivator, JolokiaContext {

    // Base filter to use for filtering out HttpServices
    public static final String HTTP_SERVICE_FILTER_BASE =
            "(" + Constants.OBJECTCLASS + "=" + HttpService.class.getName() + ")";

    // Context associated with this activator
    private BundleContext bundleContext;

    // Tracker for HttpService
    private ServiceTracker httpServiceTracker;

    // Tracker for ConfigAdmin Service
    private ServiceTracker configAdminTracker;

    // Prefix used for configuration values
    private static final String CONFIG_PREFIX = "org.jolokia";

    // Prefix used for ConfigurationAdmin pid
    private static final String CONFIG_ADMIN_PID = "org.jolokia.osgi";

    // HttpContext used for authorization
    private HttpContext jolokiaHttpContext;

    // Registration object for this JolokiaContext
    private ServiceRegistration jolokiaServiceRegistration;

    // Restrictor and associated service tracker when tracking restrictor
    // services
    private Restrictor restrictor = null;

    /** {@inheritDoc} */
    public void start(BundleContext pBundleContext) {
        bundleContext = pBundleContext;

        //Track ConfigurationAdmin service
        configAdminTracker = new ServiceTracker(pBundleContext,
                                                ConfigurationAdmin.class.getCanonicalName(),
                                                null);
        configAdminTracker.open();

        if (Boolean.parseBoolean(getConfiguration(USE_RESTRICTOR_SERVICE))) {
            // If no restrictor is set in the constructor and we are enabled to listen for a restrictor
            // service, a delegating restrictor is installed
            restrictor = new DelegatingRestrictor(bundleContext);
        }

        // Track HttpService
        if (Boolean.parseBoolean(getConfiguration(LISTEN_FOR_HTTP_SERVICE))) {
            httpServiceTracker = new ServiceTracker(pBundleContext,
                                                    buildHttpServiceFilter(pBundleContext),
                                                    new HttpServiceCustomizer(pBundleContext));
            httpServiceTracker.open();

            // Register us as JolokiaContext
            jolokiaServiceRegistration = pBundleContext.registerService(JolokiaContext.class.getCanonicalName(), this, null);
        }


    }

    /** {@inheritDoc} */
    public void stop(BundleContext pBundleContext) {
        assert pBundleContext.equals(bundleContext);

        if (httpServiceTracker != null) {
            // Closing the tracker will also call {@link HttpServiceCustomizer#removedService()}
            // for every active service which in turn unregisters the servlet
            httpServiceTracker.close();
            httpServiceTracker = null;
        }

        if (jolokiaServiceRegistration != null) {
            jolokiaServiceRegistration.unregister();
            jolokiaServiceRegistration = null;
        }

        //Shut this down last to make sure nobody calls for a property after this is shutdown
        if (configAdminTracker != null) {
            configAdminTracker.close();
            configAdminTracker = null;
        }

        restrictor = null;
        bundleContext = null;
    }

    /**
     * Get the security context for out servlet. Dependent on the configuration,
     * this is either a no-op context or one which authenticates with a given user
     *
     * @return the HttpContext with which the agent servlet gets registered.
     */
    public synchronized HttpContext getHttpContext() {
        if (jolokiaHttpContext == null) {
            final String user = getConfiguration(USER);
            if (user == null) {
                jolokiaHttpContext = new DefaultHttpContext();
            } else {
                jolokiaHttpContext = new BasicAuthenticationHttpContext(getConfiguration(REALM),
                                                                        createAuthenticator());
            }
        }
        return jolokiaHttpContext;
    }

    /**
     * Get the servlet alias under which the agen servlet is registered
     * @return get the servlet alias
     */
    public String getServletAlias() {
        return getConfiguration(AGENT_CONTEXT);
    }

    // ==================================================================================

    // Customizer for registering servlet at a HttpService
    private Dictionary<String,String> getConfiguration() {
        Dictionary<String,String> config = new Hashtable<String,String>();
        for (ConfigKey key : ConfigKey.values()) {
            String value = getConfiguration(key);
            if (value != null) {
                config.put(key.getKeyValue(),value);
            }
        }
        String jolokiaId = config.get(ConfigKey.AGENT_ID.getKeyValue());
        if (jolokiaId == null) {
            config.put(ConfigKey.AGENT_ID.getKeyValue(),
                       NetworkUtil.getAgentId(hashCode(),"osgi"));
        }
        config.put(ConfigKey.AGENT_TYPE.getKeyValue(),"osgi");
        return config;
    }


    private String getConfiguration(ConfigKey pKey) {
        // TODO: Use fragments if available.
        String value = getConfigurationFromConfigAdmin(pKey);
        if (value == null) {
            value = bundleContext.getProperty(CONFIG_PREFIX + "." + pKey.getKeyValue());
        }
        if (value == null) {
            value = pKey.getDefaultValue();
        }
        return value;
    }

    private String getConfigurationFromConfigAdmin(ConfigKey pkey) {
        ConfigurationAdmin configAdmin = (ConfigurationAdmin) configAdminTracker.getService();
        if (configAdmin == null) {
            return null;
        }
        try {
            Configuration config = configAdmin.getConfiguration(CONFIG_ADMIN_PID);
            if (config == null) {
                return null;
            }
            Dictionary props = config.getProperties();
            if (props == null) {
                return null;
            }
            return (String) props.get(CONFIG_PREFIX + "." + pkey.getKeyValue());
        } catch (IOException e) {
            return null;
        }
    }

    private Filter buildHttpServiceFilter(BundleContext pBundleContext) {
        String customFilter = getConfiguration(ConfigKey.HTTP_SERVICE_FILTER);
        String filter = customFilter != null && customFilter.trim().length() > 0 ?
                "(&" + HTTP_SERVICE_FILTER_BASE + customFilter + ")" :
                HTTP_SERVICE_FILTER_BASE;
        try {
            return pBundleContext.createFilter(filter);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException("Unable to parse filter " + filter,e);
        }
    }

    private Authenticator createAuthenticator() {
        Authenticator authenticator;
        String authMode = getConfiguration(AUTH_MODE);
        if ("basic".equalsIgnoreCase(authMode)) {
            authenticator = new BasicAuthenticator(getConfiguration(USER),getConfiguration(PASSWORD));
        } else if ("jaas".equalsIgnoreCase(authMode)) {
            authenticator = new JaasAuthenticator(getConfiguration(REALM));
        } else {
            throw new IllegalArgumentException("Unknown authentication method '" + authMode + "' configured");
        }
        return authenticator;
    }

    // =============================================================================

    private class HttpServiceCustomizer implements ServiceTrackerCustomizer {
        private final BundleContext context;

        HttpServiceCustomizer(BundleContext pContext) {
            context = pContext;
        }

        /** {@inheritDoc} */
        public Object addingService(ServiceReference reference) {
            HttpService service = (HttpService) context.getService(reference);
            try {
                service.registerServlet(getServletAlias(),
                                        new JolokiaServlet(context,restrictor),
                                        getConfiguration(),
                                        getHttpContext());
            } catch (ServletException e) {
                logError("Servlet Exception: " + e, e);
            } catch (NamespaceException e) {
                logError("Namespace Exception: " + e, e);
            }
            return service;
        }

        /** {@inheritDoc} */
        public void modifiedService(ServiceReference reference, Object service) {
        }

        /** {@inheritDoc} */
        public void removedService(ServiceReference reference, Object service) {
            HttpService httpService = (HttpService) service;
            httpService.unregister(getServletAlias());
        }
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private void logError(String message,Throwable throwable) {
        ServiceReference lRef = bundleContext.getServiceReference(LogService.class.getName());
        if (lRef != null) {
            try {
                LogService logService = (LogService) bundleContext.getService(lRef);
                if (logService != null) {
                    logService.log(LogService.LOG_ERROR,message,throwable);
                    return;
                }
            } finally {
                bundleContext.ungetService(lRef);
            }
        }
        System.err.println("Jolokia-Error: " + message + " : " + throwable.getMessage());
    }


}
