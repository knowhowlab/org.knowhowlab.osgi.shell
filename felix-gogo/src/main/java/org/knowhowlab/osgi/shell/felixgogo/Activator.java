package org.knowhowlab.osgi.shell.felixgogo;

import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.io.PrintWriter;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Felix GoGo shell adapter activator
 *
 * @author dpishchukhin
 */
public class Activator implements BundleActivator {
    private static final Logger LOG = Logger.getLogger(Activator.class.getName());

    private static final String COMMANDS_DESCRIPTION_PROPERTY = "org.knowhowlab.osgi.shell.commands";
    private static final String GROUP_ID_PROPERTY = "org.knowhowlab.osgi.shell.group.id";

    /**
     * Felix GoGo shell API supports groups.
     * Filter requires shell commands description and group id
     */
    private static final String SHELL_COMMANDS_SERVICE_FILTER = "(&" +
            "(" + COMMANDS_DESCRIPTION_PROPERTY + "=*)" +
            "(" + GROUP_ID_PROPERTY + "=*)" +
            ")";

    private static final String SUFFIX_PROPERTY = "org.knowhowlab.osgi.shell.suffix";

    /**
     * Bundle Context instance
     */
    private BundleContext bc;

    /**
     * Command provides service tracker
     */
    private ServiceTracker shellCommandsTracker;

    private Map<ServiceReference, ServiceRegistration> commandRegistrations = new HashMap<ServiceReference, ServiceRegistration>();

    public void start(BundleContext context) throws Exception {
        bc = context;
        shellCommandsTracker = new ServiceTracker(bc, bc.createFilter(SHELL_COMMANDS_SERVICE_FILTER),
                new ShellCommandsCustomizer());
        shellCommandsTracker.open();
    }

    public void stop(BundleContext bundleContext) throws Exception {
        shellCommandsTracker.close();
        shellCommandsTracker = null;

        bc = null;
    }

    /**
     * Validate Command method
     *
     * @param service     service instance
     * @param commandName command method name
     * @return <code>true</code> if method is peresent in service, <code>public</code> and
     *         has params <code>PrintStream</code> and <code>String[]</code>, otherwise - <code>false</code>
     */
    private boolean isValidCommandMethod(Object service, String commandName) {
        try {
            service.getClass().getMethod(commandName, PrintWriter.class, String[].class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Command provides service tracker customizer
     */
    private class ShellCommandsCustomizer implements ServiceTrackerCustomizer {
        private SecureRandom random = new SecureRandom();

        public Object addingService(ServiceReference reference) {
            Object groupId = reference.getProperty(GROUP_ID_PROPERTY);
            // if property value null or not String - ignore service
            if (groupId == null || !(groupId instanceof String)) {
                LOG.warning(GROUP_ID_PROPERTY + " property is null or invalid. Ignore service");
                return null;
            }
            // get commands description property
            Object commandsDescription = reference.getProperty(COMMANDS_DESCRIPTION_PROPERTY);
            // if property value null or not String[][] - ignore service
            if (commandsDescription == null) {
                LOG.warning(COMMANDS_DESCRIPTION_PROPERTY + " property is null. Ignore service");
                return null;
            } else if (!(commandsDescription instanceof String[][] || commandsDescription instanceof String[])) {
                LOG.warning(COMMANDS_DESCRIPTION_PROPERTY + " property has wrong format: not String[][] or String[]");
                return null;
            } else {
                Object service = bc.getService(reference);
                // get service ranking propety. if not null - use it on Command services registration
                String[][] commands = parseCommands(commandsDescription);
                Map<String, String> commandMap = new HashMap<String, String>();
                for (String[] commandInfo : commands) {
                    // if command info is invalid - ignore command
                    if (commandInfo != null) {
                        if (commandInfo.length != 2) {
                            LOG.warning(COMMANDS_DESCRIPTION_PROPERTY + " property has wrong format: not String[][]");
                            continue;
                        }
                        String commandName = commandInfo[0];
                        String commandHelp = commandInfo[1];
                        // if command info links to wrong method - ignore command
                        if (isValidCommandMethod(service, commandName)) {
                            commandMap.put(commandName, commandHelp);
                        }
                    }
                }
                if (!commandMap.isEmpty()) {
                    Dictionary<String, Object> props = new Hashtable<String, Object>();
                    Integer ranking = (Integer) reference.getProperty(Constants.SERVICE_RANKING);
                    Long serviceId = (Long) reference.getProperty(Constants.SERVICE_ID);
                    if (ranking != null) {
                        props.put(Constants.SERVICE_RANKING, ranking);
                    }
                    props.put("osgi.command.scope", groupId);
                    props.put("osgi.command.function", commandMap.keySet().toArray(new String[commandMap.size()]));
                    try {
                        String suffix = generateSuffix();
                        props.put(SUFFIX_PROPERTY, suffix);
                        // generate class
                        Object commandsProvider = FelixGogoCommandsServiceGenerator.generate(service, commandMap, suffix);
                        commandRegistrations.put(reference,
                                bc.registerService(commandsProvider.getClass().getName(), commandsProvider, props));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Unable to initialize group: " + groupId, e);
                    }
                    return service;
                } else {
                    return null;
                }
            }
        }

        private String generateSuffix() {
            return new BigInteger(130, random).toString(32);
        }

        private String[][] parseCommands(Object commandsDescription) {
            if (commandsDescription == null) {
                return null;
            } else if (commandsDescription instanceof String[][]) {
                return (String[][]) commandsDescription;
            } else if (commandsDescription instanceof String[]) {
                String[] commands = (String[]) commandsDescription;
                String[][] result = new String[commands.length][];
                for (int i = 0; i < commands.length; i++) {
                    String command = commands[i];
                    result[i] = command.split("#");
                }
                return result;
            } else {
                return null;
            }
        }

        public void modifiedService(ServiceReference reference, Object service) {
            // ignore
        }

        public void removedService(ServiceReference reference, Object service) {
            // unregister CommandGroup services that belongs to this service registration
            Long serviceId = (Long) reference.getProperty(Constants.SERVICE_ID);
            ServiceRegistration registration = commandRegistrations.remove(reference);
            if (registration != null) {
                String suffix = (String) registration.getReference().getProperty(SUFFIX_PROPERTY);
                registration.unregister();
                // detach class
                FelixGogoCommandsServiceGenerator.clean(suffix);
            }
            bc.ungetService(reference);
        }
    }
}
