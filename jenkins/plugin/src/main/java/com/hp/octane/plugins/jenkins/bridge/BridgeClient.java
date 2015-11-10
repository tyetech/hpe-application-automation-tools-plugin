package com.hp.octane.plugins.jenkins.bridge;

import com.hp.mqm.client.MqmRestClient;
import com.hp.mqm.client.exception.AuthenticationException;
import com.hp.mqm.client.exception.TemporarilyUnavailableException;
import com.hp.octane.plugins.jenkins.actions.PluginActions;
import com.hp.octane.plugins.jenkins.client.JenkinsMqmRestClientFactory;
import com.hp.octane.plugins.jenkins.configuration.ServerConfiguration;
import net.sf.json.JSONArray;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.Nonnull;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Created by gullery on 12/08/2015.
 * <p/>
 * This class encompasses functionality of managing connection/s to a single abridged client (MQM Server)
 */

public class BridgeClient {
	private static final Logger logger = Logger.getLogger(BridgeClient.class.getName());
	private static final String serverInstanceId = new PluginActions.ServerInfo().getInstanceId();
	private static int CONCURRENT_CONNECTIONS = 1;

	private ExecutorService connectivityExecutors = Executors.newFixedThreadPool(5, new AbridgedConnectivityExecutorsFactory());
	private ExecutorService taskProcessingExecutors = Executors.newFixedThreadPool(30, new AbridgedTasksExecutorsFactory());
	private AtomicInteger openedConnections = new AtomicInteger(0);

	private ServerConfiguration mqmConfig;
	private JenkinsMqmRestClientFactory restClientFactory;

	public BridgeClient(ServerConfiguration mqmConfig, JenkinsMqmRestClientFactory clientFactory) {
		this.mqmConfig = new ServerConfiguration(
				mqmConfig.location,
				mqmConfig.abridged,
				mqmConfig.sharedSpace,
				mqmConfig.username,
				mqmConfig.password,
				mqmConfig.impersonatedUser);
		restClientFactory = clientFactory;
		if (this.mqmConfig.location != null && !this.mqmConfig.location.isEmpty()) {
			connect();
			logger.info("BRIDGE: client initialized for '" + this.mqmConfig.location + "' (SP: " + this.mqmConfig.sharedSpace + ")");
		} else {
			logger.info("BRIDGE: client initialized in disconnected state");
		}
	}

	public void update(ServerConfiguration newConfig) {
		mqmConfig = new ServerConfiguration(
				newConfig.location,
				newConfig.abridged,
				newConfig.sharedSpace,
				newConfig.username,
				newConfig.password,
				newConfig.impersonatedUser);
		//  TODO: disconnect current connection once async connectivity is possible
		if (mqmConfig.location != null && !mqmConfig.location.isEmpty()) {
			logger.info("BRIDGE: updated for '" + mqmConfig.location + "' (SP: " + mqmConfig.sharedSpace + ")");
			connect();
		} else {
			logger.info("BRIDGE: disabled by configuration change");
		}
	}

	private void connect() {
		connectivityExecutors.execute(new Runnable() {
			@Override
			public void run() {
				String tasksJSON;
				int totalConnections;
				try {
					totalConnections = openedConnections.incrementAndGet();
					logger.info("BRIDGE: connecting to '" + mqmConfig.location +
							"' (SP: " + mqmConfig.sharedSpace +
							"; instance ID: " + serverInstanceId +
							"; self URL: " + new PluginActions.ServerInfo().getUrl() +
							")...; total connections [including new one]: " + totalConnections);
					MqmRestClient restClient = restClientFactory.create(
							mqmConfig.location,
							mqmConfig.sharedSpace,
							mqmConfig.username,
							mqmConfig.password);
					tasksJSON = restClient.getAbridgedTasks(serverInstanceId, new PluginActions.ServerInfo().getUrl());
					logger.info("BRIDGE: back from '" + mqmConfig.location + "' (SP: " + mqmConfig.sharedSpace + ") with " + (tasksJSON == null || tasksJSON.isEmpty() ? "no tasks" : "some tasks"));
					openedConnections.decrementAndGet();
					if (mqmConfig.abridged && openedConnections.get() < CONCURRENT_CONNECTIONS) {
						connect();
					}
					if (tasksJSON != null && !tasksJSON.isEmpty()) {
						dispatchTasks(tasksJSON);
					}
				} catch (AuthenticationException ae) {
					openedConnections.decrementAndGet();
					logger.severe("BRIDGE: connection to MQM Server temporary failed: authentication error");
					try {
						Thread.sleep(20000);
					} catch (InterruptedException ie) {
						logger.info("interrupted while breathing on temporary exception, continue to re-connect...");
					}
					if (mqmConfig.abridged && openedConnections.get() < CONCURRENT_CONNECTIONS) {
						connect();
					}
				} catch (TemporarilyUnavailableException tue) {
					openedConnections.decrementAndGet();
					logger.severe("BRIDGE: connection to MQM Server temporary failed: resource not available");
					try {
						Thread.sleep(20000);
					} catch (InterruptedException ie) {
						logger.info("interrupted while breathing on temporary exception, continue to re-connect...");
					}
					if (mqmConfig.abridged && openedConnections.get() < CONCURRENT_CONNECTIONS) {
						connect();
					}
				} catch (Exception e) {
					openedConnections.decrementAndGet();
					logger.severe("BRIDGE: connection to MQM Server temporary failed: " + e.getMessage());
					try {
						Thread.sleep(1000);
					} catch (InterruptedException ie) {
						logger.info("interrupted while breathing on temporary exception, continue to re-connect...");
					}
					if (mqmConfig.abridged && openedConnections.get() < CONCURRENT_CONNECTIONS) {
						connect();
					}
				}
			}
		});
	}

	private void dispatchTasks(String tasksJSON) {
		try {
			JSONArray tasks = JSONArray.fromObject(tasksJSON);
			logger.info("BRIDGE: going to process " + tasks.size() + " tasks");
			for (int i = 0; i < tasks.size(); i++) {
				taskProcessingExecutors.execute(new TaskProcessor(
						tasks.getJSONObject(i),
						restClientFactory,
						mqmConfig
				));
			}
		} catch (Exception e) {
			logger.severe("BRIDGE: failed to process tasks: " + e.getMessage());
		}
	}

	@Exported(inline = true)
	public String getLocation() {
		return mqmConfig.location;
	}

	@Exported(inline = true)
	public String getSharedSpace() {
		return mqmConfig.sharedSpace;
	}

	@Exported(inline = true)
	public String getUsername() {
		return mqmConfig.username;
	}

	private static final class AbridgedConnectivityExecutorsFactory implements ThreadFactory {

		@Override
		public Thread newThread(@Nonnull Runnable runnable) {
			Thread result = new Thread(runnable);
			result.setName("AbridgedConnectivityThread-" + result.getId());
			result.setDaemon(true);
			return result;
		}
	}

	private static final class AbridgedTasksExecutorsFactory implements ThreadFactory {

		@Override
		public Thread newThread(@Nonnull Runnable runnable) {
			Thread result = new Thread(runnable);
			result.setName("AbridgedTasksExecutorsFactory-" + result.getId());
			result.setDaemon(true);
			return result;
		}
	}
}
