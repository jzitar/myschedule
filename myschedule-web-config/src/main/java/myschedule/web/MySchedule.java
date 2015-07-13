package myschedule.web;

import myschedule.quartz.extra.SchedulerTemplate;
import myschedule.quartz.extra.util.ClasspathURLStreamHandler;
import myschedule.quartz.extra.util.Props;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.quartz.impl.RemoteScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.Map.Entry;

/**
 * This is the central manager of the MySchedule application. There is only one instance of MySchedule application, 
 * and it's used to hold all config and initialization of code. User may use the {@link #getInstance()} to retrieve it.
 * @author zemian
 */
public class MySchedule extends AbstractService {
	private static final Logger LOGGER = LoggerFactory.getLogger(MySchedule.class);
	private static volatile MySchedule instance;
	private MyScheduleSettings myScheduleSettings;
	private Map<String, SchedulerSettings> schedulerSettingsMap; //key=SettingsName
	private Map<String, SchedulerTemplate> schedulersMap;        //key=SettingsName
    private SchedulerSettingsStore schedulerSettingsStore;
    private TemplatesStore schedulerTemplatesStore;
    private TemplatesStore scriptTemplatesStore;
    private TemplatesStore xmlJobLoaderTemplatesStore;
    private String myScheduleVersion;
    private String quartzVersion;

	private MySchedule() {
	}
	
	public static MySchedule getInstance() {
		if (instance == null) {
			// Use double locking synchronized singleton pattern
			synchronized(MySchedule.class) {
				if (instance == null) {
					instance = new MySchedule();
                    instance.init();
				}
			}
		}
		return instance;
	}

    @Override
	public void initService() {
		LOGGER.debug("Initializing MySchedule ...");
		initMyScheduleSettings();
        initInternalServices();
		initSchedulerSettingsMap();
		initSchedulersMap();
        initDefaultSchedulerIfNeeded();
		LOGGER.info("MySchedule is initialized.");
	}

    @Override
    public void destroyService() {
        LOGGER.debug("Destroying MySchedule ...");
        shutdownAllSchedulers();
        delayShutdown();
        destroyInternalServices();
        LOGGER.info("MySchedule is destroyed.");
    }

    private void initMyScheduleSettings() {
        myScheduleSettings = new MyScheduleSettings();
    }

    private void initInternalServices() {
        schedulerSettingsStore = new SchedulerSettingsStore(myScheduleSettings.getSchedulerSettingsDir());
        schedulerSettingsStore.init();

        schedulerTemplatesStore = new TemplatesStore(myScheduleSettings.getSchedulerTemplatesDir(),
            ".properties",
            getDefaultSchedulerConfigsTemplates());
        schedulerTemplatesStore.init();

        scriptTemplatesStore = new TemplatesStore(myScheduleSettings.getScriptTemplatesDir(),
            "", // empty file extension for these templates set
            getDefaultScriptsTemplates());
        scriptTemplatesStore.init();

        xmlJobLoaderTemplatesStore = new TemplatesStore(myScheduleSettings.getXmlJobLoaderTemplatesDir(),
                ".xml",
                getDefaultXmlJobLoaderTemplates());
        xmlJobLoaderTemplatesStore.init();
    }

    private String[] getDefaultSchedulerConfigsTemplates() {
        String resNamePrefix = "/myschedule/web/templates/schedulerconfigs/";
        String[] result = {
            resNamePrefix + "full-config-quartz.properties",
            resNamePrefix + "in-memory-quartz.properties",
            resNamePrefix + "jmx-quartz.properties",
            resNamePrefix + "mysql-clustered-quartz.properties",
            resNamePrefix + "mysql-jee-cmt-quartz.properties",
            resNamePrefix + "mysql-quartz.properties",
            resNamePrefix + "mysql-job-histories-quartz.properties",
            resNamePrefix + "oracle-quartz.properties",
            resNamePrefix + "rmi-client-quartz.properties",
            resNamePrefix + "rmi-server-quartz.properties"
        };
        return result;
    }

    private String[] getDefaultScriptsTemplates() {
        String resNamePrefix = "/myschedule/web/templates/scripts/";
        String[] result = {
            resNamePrefix + "jobSamples.groovy",
            resNamePrefix + "simpleJobs.js",
            resNamePrefix + "cronJobs.js",
            resNamePrefix + "calendarJobs.js",
            resNamePrefix + "advanceJobs.js"
        };
        return result;
    }

    private String[] getDefaultXmlJobLoaderTemplates() {
        String resNamePrefix = "/myschedule/web/templates/xmljobloader/";
        String[] result = {
                resNamePrefix + "simpleJobs.xml",
        };
        return result;
    }

    private void destroyInternalServices() {
        schedulerSettingsStore.destroy();
        schedulerTemplatesStore.destroy();
        scriptTemplatesStore.destroy();
    }

	private void initSchedulerSettingsMap() {
		// Load up all scheduler settings from config dir files.
		schedulerSettingsMap = new HashMap<String, SchedulerSettings>();
        for (SchedulerSettings settings : schedulerSettingsStore.getAll())
            schedulerSettingsMap.put(settings.getSettingsName(), settings);
	}

    private void initDefaultSchedulerIfNeeded() {
        // Check and see if we need to auto create a default scheduler settings
        if (schedulerSettingsMap.size() == 0) {
            String propsString = getDefaultSchedulerSettingsConfigText();
            if (StringUtils.isNotBlank(propsString)) {
                LOGGER.info("Adding a new default scheduler settings.");
                addSchedulerSettings(propsString);
            }
        }
    }

	private void initSchedulersMap() {
		// Init the map first
		schedulersMap = new HashMap<String, SchedulerTemplate>();
		
		// Create and init all schedulersMap using schedulerSettingsMap
		for (SchedulerSettings settings : schedulerSettingsMap.values()) {
			if (settings.isAutoCreate()) {
                // We need to try/catch the error and continue so other schedulers may be initialize.
                try {
                    createScheduler(settings);
                } catch (Exception e) {
                    LOGGER.error("Failed to init and create scheduler instance {}", settings.getSettingsName(), e);
                }
            }
		}
	}
	
	/** Create and init scheduler and add to scheduler map. */
	public void createScheduler(SchedulerSettings settings) {
        String settingsName = settings.getSettingsName();

        // If scheduler already exists, shut it down first
        if (schedulersMap.containsKey(settingsName)) {
            LOGGER.warn("Scheduler settings {} has already been initialized. Will shutdown first.", settingsName);
            shutdownScheduler(settingsName);
        }

        // Now create the scheduler
        // Initialize Quartz scheduler. If configured, the Quartz will try to connect to DB upon init!
        LOGGER.info("Creating new Quartz scheduler from {}", settings);
        SchedulerTemplate scheduler = new SchedulerTemplate(settings.getQuartzProperties());
        schedulersMap.put(settingsName, scheduler);
        LOGGER.info("Quartz scheduler created with settings name {}", settingsName);

        // Be user friendly and prevent unwanted remote scheduler auto/start effect if possible.
        if (scheduler.getScheduler() instanceof RemoteScheduler &&
                settings.isPreventAutoStartShutdownRemoteScheduler()) {
            LOGGER.info("Scheduler settings={} has configured not to auto start on remote scheduler.", settingsName);
        } else {
            if (settings.isAutoStart()) {
                LOGGER.debug("Auto starting scheduler with settings={}", settingsName);
                scheduler.start();
                LOGGER.info("Auto started scheduler per configured settings={}", settingsName);
            }
        }
	}

	/** Shutdown scheduler and remove from scheduler map. */
	public void shutdownScheduler(String settingsName) {
		SchedulerSettings schedulerSettings = schedulerSettingsMap.get(settingsName);
		boolean waitForJobToComplete = schedulerSettings.isWaitForJobToComplete();
		LOGGER.info("Shutting down {} with waitForJobToComplete={}", schedulerSettings, waitForJobToComplete);
		SchedulerTemplate scheduler = schedulersMap.get(settingsName);
        if (scheduler != null) {
            // Be user friendly and prevent unwanted remote scheduler shutdown effect if possible.
            boolean preventShutdown = schedulerSettings.isPreventAutoStartShutdownRemoteScheduler();
            if (scheduler.getScheduler() instanceof RemoteScheduler && preventShutdown) {
                LOGGER.info("Scheduler settingsName={} has been configured NOT to shutdown remote scheduler.", settingsName);
            } else {
                if (!scheduler.isShutdown())
                    scheduler.shutdown(waitForJobToComplete);
                else
                    LOGGER.info("Scheduler settingsName={} has already been shutdown. No action.", settingsName);
            }
		    schedulersMap.remove(settingsName);
        }
	}

    /**
     * Retrieve user default scheduler config text. If not found it returns empty string, not null!
     */
    public String getDefaultSchedulerSettingsConfigText() {
        String defaultSchedulerSettingsUrl = myScheduleSettings.getDefaultSchedulerSettings();
        if (StringUtils.isEmpty(defaultSchedulerSettingsUrl)) {
            LOGGER.debug("User default scheduler settings/config text is EMPTY.");
            return "";
        } else {
            LOGGER.debug("Reading user default scheduler settings/config text url={}", defaultSchedulerSettingsUrl);
            return readText(defaultSchedulerSettingsUrl);
        }
    }

    public String readText(String urlName) {
        URL url = ClasspathURLStreamHandler.createURL(urlName);
        InputStream inStream = null;
        try {
            inStream = url.openStream();
            return IOUtils.toString(inStream);
        } catch (IOException e) {
            throw new RuntimeException("Failed read content from url=" + urlName);
        } finally {
            if (inStream != null)
                IOUtils.closeQuietly(inStream);
        }
    }
	
	/** Add to schedulerSettingsMap and scheduler map, and create the file. */
	public SchedulerSettings addSchedulerSettings(String propsString) {
        SchedulerSettings settings = schedulerSettingsStore.add(propsString);
        String settingsName = settings.getSettingsName();
		schedulerSettingsMap.put(settingsName, settings);

        if (settings.isAutoCreate())
			createScheduler(settings);

        return settings;
	}

	/** Remove from schedulerSettingsMap and scheduler map, and remove the file. */
	public void deleteSchedulerSettings(String settingsName) {
		if (schedulersMap.containsKey(settingsName)) {
			shutdownScheduler(settingsName);
		}
		
		SchedulerSettings schedulerSettings = schedulerSettingsMap.get(settingsName);
		if (schedulerSettings != null) {
            schedulerSettingsStore.remove(settingsName);
			schedulerSettingsMap.remove(settingsName);
		}
	}

    /** Update an existing scheduler settings config file. */
    public void updateSchedulerSettings(String settingsName, String propsString) {
        if (schedulersMap.containsKey(settingsName)) {
            shutdownScheduler(settingsName);
        }

        SchedulerSettings schedulerSettings = schedulerSettingsMap.get(settingsName);
        if (schedulerSettings != null) {
            // Update and reload a new settings instance.
            schedulerSettings = schedulerSettingsStore.update(settingsName, propsString);
            schedulerSettingsMap.put(settingsName, schedulerSettings);

            // Create the scheduler instance if needed
            if (schedulerSettings.isAutoCreate())
                createScheduler(schedulerSettings);
        }
    }

	private void shutdownAllSchedulers() {
		for (String settingsName : schedulerSettingsMap.keySet()) {
            try {
			    shutdownScheduler(settingsName);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to shutdown scheduler settingsName={}.", settingsName);
            }
		}
	}

	private void delayShutdown() {
		// Tomcat is known to spit out bogus error messages if we don't slow down a bit here, so we purposely delay
		// briefly. User may configured to zero it out if their server do not have this problem.
		long pauseTime = myScheduleSettings.getPauseTimeAfterShutdown();
		if (pauseTime > 0) {
			try {
				LOGGER.debug("Pausing {}ms after all schedulersMap shutdown to avoid web server problem.", pauseTime);
				Thread.sleep(pauseTime); 
			} catch (InterruptedException e) {
                // Can not sleep? Oh well, we are shutting down anyway, so just ignore and continue.
			}
		}
	}
	
	public List<String> getSchedulerSettingsNames() {
		return new ArrayList<String>(schedulerSettingsMap.keySet());
	}
	
	/**
	 * Get the settings names and full names of all schedulers defined in myschedule.
	 * @return Map of <settings name, full name>
	 */
	public Map<String, String> getSchedulerNames(){
		Map<String, String> schedulerNames = new HashMap<String, String>();
		for(Entry<String, SchedulerSettings> entry: schedulerSettingsMap.entrySet()){
			SchedulerSettings settings = entry.getValue();
			schedulerNames.put(entry.getKey(), settings.getSchedulerFullName());
		}
		return schedulerNames;
	}

	/**
	 * Get the settings names and corresponding schedulers of all schedulers defined in myschedule.
	 * @return Map of <settings name, scheduler>
	 */
	public Map<String, SchedulerTemplate> getSchedulers(){
		return schedulersMap;
	}
	
	public SchedulerSettings getSchedulerSettings(String settingsName) {
		return schedulerSettingsMap.get(settingsName);
	}

    public String getSchedulerSettingsConfig(String settingsName) {
        return schedulerSettingsStore.getConfigText(settingsName);
    }
	
	public SchedulerTemplate getScheduler(String settingsName) {
		return schedulersMap.get(settingsName);
	}

    public static SchedulerStatus getSchedulerStatus(SchedulerTemplate scheduler) {
        if (scheduler == null)
            return SchedulerStatus.SHUTDOWN;

        else if (scheduler.isShutdown())
            return SchedulerStatus.SHUTDOWN;
        else if (scheduler.isInStandbyMode())
            return SchedulerStatus.STANDBY;
        else if (scheduler.isStarted())
            return SchedulerStatus.RUNNING;

        return SchedulerStatus.SHUTDOWN;
    }

    public TemplatesStore getSchedulerTemplatesStore() {
        return schedulerTemplatesStore;
    }

    public TemplatesStore getScriptTemplatesStore() {
        return scriptTemplatesStore;
    }

    public TemplatesStore getXmlJobLoaderTemplatesStore() {
        return xmlJobLoaderTemplatesStore;
    }

    public MyScheduleSettings getMyScheduleSettings() {
        return myScheduleSettings;
    }

    public String getMyScheduleVersion() {
        if (myScheduleVersion == null) {
            String res = "META-INF/maven/myschedule/myschedule-web-config/pom.properties";
            myScheduleVersion = readPropertyValueFromResource(res, "version");
        }
        return myScheduleVersion;
    }

    public String getQuartzVersion() {
        if (quartzVersion == null) {
            String res = "META-INF/maven/org.quartz-scheduler/quartz/pom.properties";
            quartzVersion = readPropertyValueFromResource(res, "version");
        }
        return quartzVersion;
    }

    /** Return key found from resource properties file, or empty string. */
    private String readPropertyValueFromResource(String resourceName, String key) {
        String result = "";
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            InputStream inStream = classLoader.getResourceAsStream(resourceName);
            if (inStream != null) {
                try {
                    Props props = new Props();
                    props.load(inStream);
                    result = props.getString(key);
                } finally {
                    IOUtils.closeQuietly(inStream);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Not able to get " + key + " from {}", resourceName, e); // Use DEBUG on purpose so not to be noisy.
        }
        return result;
    }
}
