/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.spring.sessions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ignite.Ignite;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.spring.sessions.proxy.ClientSessionProxy;
import org.apache.ignite.spring.sessions.proxy.IgniteSessionProxy;
import org.apache.ignite.spring.sessions.proxy.SessionProxy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportAware;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.session.FlushMode;
import org.springframework.session.IndexResolver;
import org.springframework.session.MapSession;
import org.springframework.session.SaveMode;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.config.annotation.web.http.SpringHttpSessionConfiguration;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.util.StringUtils;

/**
 * Exposes the {@link SessionRepositoryFilter} as a bean named
 * {@code springSessionRepositoryFilter}. In order to use this a single {@link Ignite}
 * must be exposed as a Bean.
 */
@Configuration(proxyBeanMethods = false)
public class IgniteHttpSessionConfiguration extends SpringHttpSessionConfiguration implements ImportAware {
    /** */
    private Integer maxInactiveIntervalInSeconds = MapSession.DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS;

    /** */
    private String sessionMapName = IgniteIndexedSessionRepository.DEFAULT_SESSION_MAP_NAME;

    /** */
    private FlushMode flushMode = FlushMode.ON_SAVE;

    /** */
    private SaveMode saveMode = SaveMode.ON_SET_ATTRIBUTE;

    /** */
    private SessionProxy sessions;

    /** */
    private ApplicationEventPublisher applicationEventPublisher;

    /** */
    private IndexResolver<Session> indexResolver;

    /** */
    private List<SessionRepositoryCustomizer<IgniteIndexedSessionRepository>> sessionRepositoryCustomizers;

    /**
     * @return Session repository.
     */
    @Bean
    public SessionRepository<?> sessionRepository() {
        return createIgniteIndexedSessionRepository();
    }

    /**
     * @param maxInactiveIntervalInSeconds Maximum inactive interval in sec.
     */
    public void setMaxInactiveIntervalInSeconds(int maxInactiveIntervalInSeconds) {
        this.maxInactiveIntervalInSeconds = maxInactiveIntervalInSeconds;
    }

    /**
     * @param sessionMapName Session map name.
     */
    public void setSessionMapName(String sessionMapName) {
        this.sessionMapName = sessionMapName;
    }

    /**
     * @param flushMode Flush mode.
     */
    public void setFlushMode(FlushMode flushMode) {
        this.flushMode = flushMode;
    }

    /**
     * @param saveMode Save mode.
     */
    public void setSaveMode(SaveMode saveMode) {
        this.saveMode = saveMode;
    }

    /**
     * @param springSessionIgnite Ignite session.
     * @param ignite Ignite instance provider.
     */
    @Autowired
    public void setSessions(
        @SpringSessionIgnite ObjectProvider<Object> springSessionIgnite,
        ObjectProvider<Ignite> ignite,
        ObjectProvider<IgniteClient> cli
    ) {
        Object connObj = springSessionIgnite.getIfAvailable();

        if (connObj == null)
            connObj = ignite.getIfAvailable();

        if (connObj == null)
            connObj = cli.getIfAvailable();

        this.sessions = createSessionProxy(connObj);
    }

    /**
     * @param applicationEventPublisher Application event publisher.
     */
    @Autowired
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * @param indexResolver Index resolver.
     */
    @Autowired(required = false)
    public void setIndexResolver(IndexResolver<Session> indexResolver) {
        this.indexResolver = indexResolver;
    }

    /**
     * @param sessionRepositoryCustomizers Session repository customizer.
     */
    @Autowired(required = false)
    public void setSessionRepositoryCustomizer(
            ObjectProvider<SessionRepositoryCustomizer<IgniteIndexedSessionRepository>> sessionRepositoryCustomizers) {
        this.sessionRepositoryCustomizers = sessionRepositoryCustomizers.orderedStream().collect(Collectors.toList());
    }

    /**
     * @param importMetadata Annotation metadata.
     */
    @Override public void setImportMetadata(AnnotationMetadata importMetadata) {
        Map<String, Object> attributeMap = importMetadata
                .getAnnotationAttributes(EnableIgniteHttpSession.class.getName());
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(attributeMap);
        this.maxInactiveIntervalInSeconds = attributes.getNumber("maxInactiveIntervalInSeconds");
        String sessionMapNameValue = attributes.getString("sessionMapName");
        if (StringUtils.hasText(sessionMapNameValue))
            this.sessionMapName = sessionMapNameValue;

        this.flushMode = attributes.getEnum("flushMode");
        this.saveMode = attributes.getEnum("saveMode");
    }

    /** */
    private SessionProxy createSessionProxy(Object connObj) {
        List<SqlFieldsQuery> initQueries = Arrays.asList(
            new SqlFieldsQuery("CREATE TABLE IF NOT EXISTS IgniteSession (" +
                " id VARCHAR PRIMARY KEY," +
                " delegate OTHER," +
                " principal VARCHAR" +
                ") WITH \"template=replicated,atomicity=atomic,value_type=org.apache.ignite.spring.sessions.IgniteIndexedSessionRepository$IgniteSession,cache_name=" + sessionMapName + "\""),
            new SqlFieldsQuery("CREATE INDEX IF NOT EXISTS ignitesession_principal_idx ON IgniteSession (principal);")
        );

        if (connObj instanceof IgniteEx) {
            IgniteEx ignite = (IgniteEx)connObj;

            for (SqlFieldsQuery qry : initQueries)
                U.closeQuiet(ignite.context().query().querySqlFields(qry, true));

            return new IgniteSessionProxy(ignite.cache(sessionMapName));
        }

        if (connObj instanceof IgniteClient) {
            IgniteClient cli = (IgniteClient)connObj;

            for (SqlFieldsQuery qry : initQueries)
                cli.query(qry).getAll();

            return new ClientSessionProxy(cli.cache(sessionMapName));
        }

        throw new IllegalArgumentException(
            "Object " + connObj + " can not be used to connect to the Ignite cluster.");
    }

    /** */
    private IgniteIndexedSessionRepository createIgniteIndexedSessionRepository() {
        IgniteIndexedSessionRepository sessionRepository = new IgniteIndexedSessionRepository(this.sessions);
        sessionRepository.setApplicationEventPublisher(this.applicationEventPublisher);
        if (this.indexResolver != null)
            sessionRepository.setIndexResolver(this.indexResolver);

        sessionRepository.setDefaultMaxInactiveInterval(this.maxInactiveIntervalInSeconds);
        sessionRepository.setFlushMode(this.flushMode);
        sessionRepository.setSaveMode(this.saveMode);
        this.sessionRepositoryCustomizers
                .forEach((sessionRepositoryCustomizer) -> sessionRepositoryCustomizer.customize(sessionRepository));
        return sessionRepository;
    }
}
