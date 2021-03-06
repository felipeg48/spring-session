/*
 * Copyright 2014-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.session.hazelcast;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.query.Predicates;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.events.AbstractSessionEvent;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.util.Assert;

/**
 * A {@link org.springframework.session.SessionRepository} implementation that stores
 * sessions in Hazelcast's distributed {@link IMap}.
 *
 * <p>
 * An example of how to create a new instance can be seen below:
 *
 * <pre class="code">
 * Config config = new Config();
 *
 * // ... configure Hazelcast ...
 *
 * HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(config);
 *
 * IMap{@code <String, MapSession>} sessions = hazelcastInstance
 *         .getMap("spring:session:sessions");
 *
 * HazelcastSessionRepository sessionRepository =
 *         new HazelcastSessionRepository(sessions);
 * </pre>
 *
 * In order to support finding sessions by principal name using
 * {@link #findByIndexNameAndIndexValue(String, String)} method, custom configuration of
 * {@code IMap} supplied to this implementation is required.
 *
 * The following snippet demonstrates how to define required configuration using
 * programmatic Hazelcast Configuration:
 *
 * <pre class="code">
 * MapAttributeConfig attributeConfig = new MapAttributeConfig()
 *         .setName(HazelcastSessionRepository.PRINCIPAL_NAME_ATTRIBUTE)
 *         .setExtractor(PrincipalNameExtractor.class.getName());
 *
 * Config config = new Config();
 *
 * config.getMapConfig("spring:session:sessions")
 *         .addMapAttributeConfig(attributeConfig)
 *         .addMapIndexConfig(new MapIndexConfig(
 *                 HazelcastSessionRepository.PRINCIPAL_NAME_ATTRIBUTE, false));
 *
 * Hazelcast.newHazelcastInstance(config);
 * </pre>
 *
 * This implementation listens for events on the Hazelcast-backed SessionRepository and
 * translates those events into the corresponding Spring Session events. Publish the
 * Spring Session events with the given {@link ApplicationEventPublisher}.
 *
 * <ul>
 * <li>entryAdded - {@link SessionCreatedEvent}</li>
 * <li>entryEvicted - {@link SessionExpiredEvent}</li>
 * <li>entryRemoved - {@link SessionDeletedEvent}</li>
 * </ul>
 *
 * @author Vedran Pavic
 * @author Tommy Ludwig
 * @author Mark Anderson
 * @since 1.3.0
 */
public class HazelcastSessionRepository implements
		FindByIndexNameSessionRepository<MapSession>,
		EntryAddedListener<String, MapSession>,
		EntryEvictedListener<String, MapSession>,
		EntryRemovedListener<String, MapSession> {

	/**
	 * The principal name custom attribute name.
	 */
	public static final String PRINCIPAL_NAME_ATTRIBUTE = "principalName";

	private static final Log logger = LogFactory.getLog(HazelcastSessionRepository.class);

	private final IMap<String, MapSession> sessions;

	private ApplicationEventPublisher eventPublisher = new ApplicationEventPublisher() {

		public void publishEvent(ApplicationEvent event) {
		}

		public void publishEvent(Object event) {
		}

	};

	/**
	 * If non-null, this value is used to override
	 * {@link MapSession#setMaxInactiveIntervalInSeconds(int)}.
	 */
	private Integer defaultMaxInactiveInterval;

	private String sessionListenerId;

	public HazelcastSessionRepository(IMap<String, MapSession> sessions) {
		Assert.notNull(sessions, "Sessions IMap must not be null");
		this.sessions = sessions;
	}

	@PostConstruct
	private void init() {
		this.sessionListenerId = this.sessions.addEntryListener(this, true);
	}

	@PreDestroy
	private void close() {
		this.sessions.removeEntryListener(this.sessionListenerId);
	}

	/**
	 * Sets the {@link ApplicationEventPublisher} that is used to publish
	 * {@link AbstractSessionEvent session events}. The default is to not publish session
	 * events.
	 *
	 * @param applicationEventPublisher the {@link ApplicationEventPublisher} that is used
	 * to publish session events. Cannot be null.
	 */
	public void setApplicationEventPublisher(
			ApplicationEventPublisher applicationEventPublisher) {
		Assert.notNull(applicationEventPublisher,
				"ApplicationEventPublisher cannot be null");
		this.eventPublisher = applicationEventPublisher;
	}

	/**
	 * Set the maximum inactive interval in seconds between requests before newly created
	 * sessions will be invalidated. A negative time indicates that the session will never
	 * timeout. The default is 1800 (30 minutes).
	 * @param defaultMaxInactiveInterval the maximum inactive interval in seconds
	 */
	public void setDefaultMaxInactiveInterval(Integer defaultMaxInactiveInterval) {
		this.defaultMaxInactiveInterval = defaultMaxInactiveInterval;
	}

	public MapSession createSession() {
		MapSession result = new MapSession();
		if (this.defaultMaxInactiveInterval != null) {
			result.setMaxInactiveIntervalInSeconds(this.defaultMaxInactiveInterval);
		}
		return result;
	}

	public void save(MapSession session) {
		this.sessions.put(session.getId(), session,
				session.getMaxInactiveIntervalInSeconds(), TimeUnit.SECONDS);
	}

	public MapSession getSession(String id) {
		MapSession saved = this.sessions.get(id);
		if (saved == null) {
			return null;
		}
		if (saved.isExpired()) {
			delete(saved.getId());
			return null;
		}
		return saved;
	}

	public void delete(String id) {
		this.sessions.remove(id);
	}

	public Map<String, MapSession> findByIndexNameAndIndexValue(
			String indexName, String indexValue) {
		if (!PRINCIPAL_NAME_INDEX_NAME.equals(indexName)) {
			return Collections.emptyMap();
		}
		Collection<MapSession> sessions = this.sessions.values(
				Predicates.equal(PRINCIPAL_NAME_ATTRIBUTE, indexValue));
		Map<String, MapSession> sessionMap = new HashMap<String, MapSession>(
				sessions.size());
		for (MapSession session : sessions) {
			sessionMap.put(session.getId(), session);
		}
		return sessionMap;
	}

	public void entryAdded(EntryEvent<String, MapSession> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session created with id: " + event.getValue().getId());
		}
		this.eventPublisher.publishEvent(new SessionCreatedEvent(this, event.getValue()));
	}

	public void entryEvicted(EntryEvent<String, MapSession> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session expired with id: " + event.getOldValue().getId());
		}
		this.eventPublisher
				.publishEvent(new SessionExpiredEvent(this, event.getOldValue()));
	}

	public void entryRemoved(EntryEvent<String, MapSession> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session deleted with id: " + event.getOldValue().getId());
		}
		this.eventPublisher
				.publishEvent(new SessionDeletedEvent(this, event.getOldValue()));
	}

}
