/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.schema;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import lombok.Getter;
import org.apache.shardingsphere.cluster.heartbeat.event.HeartbeatDetectNoticeEvent;
import org.apache.shardingsphere.cluster.heartbeat.eventbus.HeartbeatEventBus;
import org.apache.shardingsphere.infra.auth.Authentication;
import org.apache.shardingsphere.infra.config.RuleConfiguration;
import org.apache.shardingsphere.infra.config.properties.ConfigurationProperties;
import org.apache.shardingsphere.infra.config.properties.ConfigurationPropertyKey;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.database.type.DatabaseTypes;
import org.apache.shardingsphere.infra.exception.ShardingSphereException;
import org.apache.shardingsphere.infra.executor.kernel.ExecutorKernel;
import org.apache.shardingsphere.infra.executor.sql.ConnectionMode;
import org.apache.shardingsphere.infra.log.ConfigurationLogger;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.metadata.schema.RuleSchemaMetaData;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.rule.ShardingSphereRulesBuilder;
import org.apache.shardingsphere.infra.rule.StatusContainedRule;
import org.apache.shardingsphere.infra.rule.event.impl.DataSourceNameDisabledEvent;
import org.apache.shardingsphere.kernal.context.SchemaContext;
import org.apache.shardingsphere.kernal.context.SchemaContexts;
import org.apache.shardingsphere.kernal.context.SchemaContextsBuilder;
import org.apache.shardingsphere.kernal.context.runtime.RuntimeContext;
import org.apache.shardingsphere.kernal.context.schema.DataSourceParameter;
import org.apache.shardingsphere.kernal.context.schema.ShardingSphereSchema;
import org.apache.shardingsphere.orchestration.core.common.event.AuthenticationChangedEvent;
import org.apache.shardingsphere.orchestration.core.common.event.DataSourceChangedEvent;
import org.apache.shardingsphere.orchestration.core.common.event.PropertiesChangedEvent;
import org.apache.shardingsphere.orchestration.core.common.event.RuleConfigurationsChangedEvent;
import org.apache.shardingsphere.orchestration.core.common.event.SchemaAddedEvent;
import org.apache.shardingsphere.orchestration.core.common.event.SchemaDeletedEvent;
import org.apache.shardingsphere.orchestration.core.common.eventbus.ShardingOrchestrationEventBus;
import org.apache.shardingsphere.orchestration.core.metadatacenter.event.MetaDataChangedEvent;
import org.apache.shardingsphere.orchestration.core.registrycenter.event.CircuitStateChangedEvent;
import org.apache.shardingsphere.orchestration.core.registrycenter.event.DisabledStateChangedEvent;
import org.apache.shardingsphere.orchestration.core.registrycenter.schema.OrchestrationSchema;
import org.apache.shardingsphere.proxy.backend.BackendDataSource;
import org.apache.shardingsphere.proxy.backend.cluster.HeartbeatHandler;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.datasource.JDBCBackendDataSourceFactory;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.datasource.JDBCRawBackendDataSourceFactory;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.recognizer.JDBCDriverURLRecognizerEngine;
import org.apache.shardingsphere.proxy.backend.util.DataSourceConverter;
import org.apache.shardingsphere.transaction.core.TransactionType;
import org.apache.shardingsphere.transaction.spi.ShardingTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

@Getter
public final class ProxySchemaContexts {
    
    private static final ProxySchemaContexts INSTANCE = new ProxySchemaContexts();
    
    private final JDBCBackendDataSourceFactory dataSourceFactory = JDBCRawBackendDataSourceFactory.getInstance();
    
    private SchemaContexts schemaContexts = new SchemaContexts();
    
    private final JDBCBackendDataSource backendDataSource = new JDBCBackendDataSource();
    
    private boolean isCircuitBreak;
    
    private ProxySchemaContexts() {
        ShardingOrchestrationEventBus.getInstance().register(this);
        HeartbeatEventBus.getInstance().register(this);
    }
    
    /**
     * Get instance of proxy schema schemas.
     *
     * @return instance of ShardingSphere schemas.
     */
    public static ProxySchemaContexts getInstance() {
        return INSTANCE;
    }
    
    /**
     * Initialize proxy schema contexts.
     *
     * @param schemaDataSources data source map
     * @param schemaRules schema rule map
     * @param authentication authentication
     * @param properties properties
     * @throws SQLException SQL exception
     */
    public void init(final Map<String, Map<String, DataSourceParameter>> schemaDataSources, final Map<String, Collection<RuleConfiguration>> schemaRules, 
                     final Authentication authentication, final Properties properties) throws SQLException {
        DatabaseType databaseType = DatabaseTypes.getActualDatabaseType(
                JDBCDriverURLRecognizerEngine.getJDBCDriverURLRecognizer(schemaDataSources.values().iterator().next().values().iterator().next().getUrl()).getDatabaseType());
        SchemaContextsBuilder schemaContextsBuilder = 
                new SchemaContextsBuilder(createDataSourcesMap(schemaDataSources), schemaDataSources, authentication, databaseType, schemaRules, properties);
        schemaContexts = schemaContextsBuilder.build();
    }
    
    private Map<String, Map<String, DataSource>> createDataSourcesMap(final Map<String, Map<String, DataSourceParameter>> schemaDataSources) {
        Map<String, Map<String, DataSource>> result = new LinkedHashMap<>();
        for (Entry<String, Map<String, DataSourceParameter>> entry : schemaDataSources.entrySet()) {
            result.put(entry.getKey(), createDataSources(entry.getValue()));
        }
        return result;
    }
    
    private Map<String, DataSource> createDataSources(final Map<String, DataSourceParameter> dataSourceParameters) {
        Map<String, DataSource> result = new LinkedHashMap<>(dataSourceParameters.size(), 1);
        for (Entry<String, DataSourceParameter> entry : dataSourceParameters.entrySet()) {
            try {
                result.put(entry.getKey(), dataSourceFactory.build(entry.getKey(), entry.getValue()));
                // CHECKSTYLE:OFF
            } catch (final Exception ex) {
                // CHECKSTYLE:ON
                throw new ShardingSphereException(String.format("Can not build data source, name is `%s`.", entry.getKey()), ex);
            }
        }
        return result;
    }
    
    /**
     * Check schema exists.
     *
     * @param schema schema
     * @return schema exists or not
     */
    public boolean schemaExists(final String schema) {
        return null != schemaContexts && schemaContexts.getSchemaContexts().containsKey(schema);
    }
    
    /**
     * Get ShardingSphere schema.
     *
     * @param schemaName schema name
     * @return ShardingSphere schema
     */
    public SchemaContext getSchema(final String schemaName) {
        return Strings.isNullOrEmpty(schemaName) ? null : schemaContexts.getSchemaContexts().get(schemaName);
    }
    
    /**
     * Get schema names.
     *
     * @return schema names
     */
    public List<String> getSchemaNames() {
        return new LinkedList<>(schemaContexts.getSchemaContexts().keySet());
    }
    
    /**
     * Renew to add new schema.
     *
     * @param schemaAddedEvent schema add changed event
     * @throws SQLException SQL exception
     */
    @Subscribe
    public synchronized void renew(final SchemaAddedEvent schemaAddedEvent) throws SQLException {
        Map<String, SchemaContext> schemas = new HashMap<>(getSchemaContexts().getSchemaContexts());
        schemas.put(schemaAddedEvent.getShardingSchemaName(), getAddedSchemaContext(schemaAddedEvent));
        schemaContexts = new SchemaContexts(schemas, getSchemaContexts().getProperties(), getSchemaContexts().getAuthentication());
    }
    
    /**
     * Renew to delete new schema.
     *
     * @param schemaDeletedEvent schema delete changed event
     */
    @Subscribe
    public synchronized void renew(final SchemaDeletedEvent schemaDeletedEvent) {
        Map<String, SchemaContext> schemas = new HashMap<>(getSchemaContexts().getSchemaContexts());
        schemas.remove(schemaDeletedEvent.getShardingSchemaName());
        schemaContexts = new SchemaContexts(schemas, getSchemaContexts().getProperties(), getSchemaContexts().getAuthentication());
    }
    
    /**
     * Renew properties.
     *
     * @param event properties changed event
     */
    @Subscribe
    public synchronized void renew(final PropertiesChangedEvent event) {
        ConfigurationLogger.log(event.getProps());
        ConfigurationProperties properties = new ConfigurationProperties(event.getProps());
        schemaContexts = new SchemaContexts(getChangedSchemaContexts(properties), properties, getSchemaContexts().getAuthentication());
    }
    
    /**
     * Renew authentication.
     *
     * @param event authentication changed event
     */
    @Subscribe
    public synchronized void renew(final AuthenticationChangedEvent event) {
        ConfigurationLogger.log(event.getAuthentication());
        schemaContexts = new SchemaContexts(getSchemaContexts().getSchemaContexts(), getSchemaContexts().getProperties(), event.getAuthentication());
    }
    
    /**
     * Renew circuit breaker state.
     *
     * @param event circuit state changed event
     */
    @Subscribe
    public synchronized void renew(final CircuitStateChangedEvent event) {
        isCircuitBreak = event.isCircuitBreak();
    }
    
    /**
     * Renew meta data of the schema.
     *
     * @param event meta data changed event.
     */
    @Subscribe
    public synchronized void renew(final MetaDataChangedEvent event) {
        Map<String, SchemaContext> schemaContexts = new HashMap<>(this.schemaContexts.getSchemaContexts().size());
        for (Entry<String, SchemaContext> entry : this.schemaContexts.getSchemaContexts().entrySet()) { 
            if (event.getSchemaNames().contains(entry.getKey())) {
                schemaContexts.put(entry.getKey(), new SchemaContext(entry.getValue().getName(), 
                        getChangedShardingSphereSchema(entry.getValue().getSchema(), event.getRuleSchemaMetaData()), entry.getValue().getRuntimeContext()));
            } else {
                schemaContexts.put(entry.getKey(), entry.getValue());
            }
        }
        this.schemaContexts = new SchemaContexts(schemaContexts, this.schemaContexts.getProperties(), this.schemaContexts.getAuthentication());
    }
    
    /**
     * Renew rule configurations.
     *
     * @param ruleConfigurationsChangedEvent rule configurations changed event.
     */
    @Subscribe
    public synchronized void renew(final RuleConfigurationsChangedEvent ruleConfigurationsChangedEvent) {
        Map<String, SchemaContext> schemaContexts = new HashMap<>(this.schemaContexts.getSchemaContexts());
        String schemaName = ruleConfigurationsChangedEvent.getShardingSchemaName();
        schemaContexts.remove(schemaName);
        schemaContexts.put(schemaName, getChangedSchemaContext(this.schemaContexts.getSchemaContexts().get(schemaName), ruleConfigurationsChangedEvent.getRuleConfigurations()));
        this.schemaContexts = new SchemaContexts(schemaContexts, this.schemaContexts.getProperties(), this.schemaContexts.getAuthentication());
    }
    
    /**
     * Renew disabled data source names.
     *
     * @param disabledStateChangedEvent disabled state changed event
     */
    @Subscribe
    public synchronized void renew(final DisabledStateChangedEvent disabledStateChangedEvent) {
        OrchestrationSchema orchestrationSchema = disabledStateChangedEvent.getOrchestrationSchema();
        Collection<ShardingSphereRule> rules = schemaContexts.getSchemaContexts().get(orchestrationSchema.getSchemaName()).getSchema().getRules();
        for (ShardingSphereRule each : rules) {
            if (each instanceof StatusContainedRule) {
                ((StatusContainedRule) each).updateRuleStatus(new DataSourceNameDisabledEvent(orchestrationSchema.getDataSourceName(), disabledStateChangedEvent.isDisabled()));
            }
        }
    }
    
    /**
     * Renew data source configuration.
     *
     * @param dataSourceChangedEvent data source changed event.
     * @throws Exception exception
     */
    @Subscribe
    public synchronized void renew(final DataSourceChangedEvent dataSourceChangedEvent) throws Exception {
        String schemaName = dataSourceChangedEvent.getShardingSchemaName();
        Map<String, DataSourceParameter> newDataSourceParameters = DataSourceConverter.getDataSourceParameterMap(dataSourceChangedEvent.getDataSourceConfigurations());
        Map<String, SchemaContext> schemaContexts = new HashMap<>(this.schemaContexts.getSchemaContexts());
        schemaContexts.remove(schemaName);
        schemaContexts.put(schemaName, getChangedSchemaContext(this.schemaContexts.getSchemaContexts().get(schemaName), newDataSourceParameters));
        this.schemaContexts = new SchemaContexts(schemaContexts, this.schemaContexts.getProperties(), this.schemaContexts.getAuthentication());
    }
    
    /**
     * Heart beat detect.
     *
     * @param event heart beat detect notice event
     */
    @Subscribe
    public synchronized void heartbeat(final HeartbeatDetectNoticeEvent event) {
        HeartbeatHandler.getInstance().handle(schemaContexts.getSchemaContexts());
    }
    
    private SchemaContext getAddedSchemaContext(final SchemaAddedEvent schemaAddedEvent) throws SQLException {
        String schemaName = schemaAddedEvent.getShardingSchemaName();
        Map<String, DataSourceParameter> dataSourceParameters = DataSourceConverter.getDataSourceParameterMap(schemaAddedEvent.getDataSourceConfigurations());
        Map<String, Map<String, DataSourceParameter>> dataSourceParametersMap = Collections.singletonMap(schemaName, dataSourceParameters);
        DatabaseType databaseType = schemaContexts.getSchemaContexts().values().iterator().next().getSchema().getDatabaseType();
        SchemaContextsBuilder schemaContextsBuilder = new SchemaContextsBuilder(createDataSourcesMap(dataSourceParametersMap), dataSourceParametersMap,
                schemaContexts.getAuthentication(), databaseType, Collections.singletonMap(schemaName, schemaAddedEvent.getRuleConfigurations()),
                schemaContexts.getProperties().getProps());
        return schemaContextsBuilder.build().getSchemaContexts().get(schemaName);
    }
    
    private Map<String, SchemaContext> getChangedSchemaContexts(final ConfigurationProperties properties) {
        Map<String, SchemaContext> result = new HashMap<>(getSchemaContexts().getSchemaContexts().size());
        for (Entry<String, SchemaContext> entry : this.schemaContexts.getSchemaContexts().entrySet()) {
            RuntimeContext runtimeContext = entry.getValue().getRuntimeContext();
            result.put(entry.getKey(), new SchemaContext(entry.getValue().getName(), entry.getValue().getSchema(), new RuntimeContext(runtimeContext.getCachedDatabaseMetaData(),
                    new ExecutorKernel(properties.<Integer>getValue(ConfigurationPropertyKey.EXECUTOR_SIZE)), runtimeContext.getSqlParserEngine(), runtimeContext.getTransactionManagerEngine())));
        }
        return result;
    }
    
    private ShardingSphereSchema getChangedShardingSphereSchema(final ShardingSphereSchema oldShardingSphereSchema, final RuleSchemaMetaData newRuleSchemaMetaData) {
        ShardingSphereMetaData metaData = new ShardingSphereMetaData(oldShardingSphereSchema.getMetaData().getDataSources(), newRuleSchemaMetaData);
        return new ShardingSphereSchema(oldShardingSphereSchema.getDatabaseType(), oldShardingSphereSchema.getConfigurations(),
                oldShardingSphereSchema.getRules(), oldShardingSphereSchema.getDataSources(), metaData);
    }
    
    private SchemaContext getChangedSchemaContext(final SchemaContext schemaContext, final Collection<RuleConfiguration> configurations) {
        ShardingSphereSchema oldSchema = schemaContext.getSchema();
        ShardingSphereSchema newSchema = new ShardingSphereSchema(oldSchema.getDatabaseType(), configurations,
                ShardingSphereRulesBuilder.build(configurations, oldSchema.getDataSources().keySet()), oldSchema.getDataSources(), oldSchema.getDataSourceParameters(), oldSchema.getMetaData());
        return new SchemaContext(schemaContext.getName(), newSchema, schemaContext.getRuntimeContext());
    }
    
    private SchemaContext getChangedSchemaContext(final SchemaContext oldSchemaContext, final Map<String, DataSourceParameter> newDataSourceParameters) throws Exception {
        Map<String, DataSourceParameter> oldDataSourceParameters = oldSchemaContext.getSchema().getDataSourceParameters();
        List<String> deletedDataSourceParameters = getDeletedDataSources(oldDataSourceParameters, newDataSourceParameters);
        Map<String, DataSourceParameter> modifiedDataSourceParameters = getModifiedDataSources(oldDataSourceParameters, newDataSourceParameters);
        oldSchemaContext.getSchema().closeDataSources(deletedDataSourceParameters);
        oldSchemaContext.getSchema().closeDataSources(modifiedDataSourceParameters.keySet());
        oldSchemaContext.getRuntimeContext().getTransactionManagerEngine().close();
        Map<String, DataSource> newDataSources = getNewDataSources(oldSchemaContext.getSchema().getDataSources(),
                deletedDataSourceParameters, getAddedDataSourceParameters(oldDataSourceParameters, newDataSourceParameters), modifiedDataSourceParameters);
        return new SchemaContextsBuilder(Collections.singletonMap(oldSchemaContext.getName(), newDataSources), Collections.singletonMap(oldSchemaContext.getName(), newDataSourceParameters),
                this.schemaContexts.getAuthentication(), oldSchemaContext.getSchema().getDatabaseType(), Collections.singletonMap(oldSchemaContext.getName(),
                oldSchemaContext.getSchema().getConfigurations()), this.schemaContexts.getProperties().getProps()).build().getSchemaContexts().get(oldSchemaContext.getName());
    }
    
    private synchronized List<String> getDeletedDataSources(final Map<String, DataSourceParameter> oldDataSourceParameters, final Map<String, DataSourceParameter> newDataSourceParameters) {
        List<String> result = new LinkedList<>(oldDataSourceParameters.keySet());
        result.removeAll(newDataSourceParameters.keySet());
        return result;
    }
    
    private synchronized Map<String, DataSourceParameter> getAddedDataSourceParameters(final Map<String, DataSourceParameter> oldDataSourceParameters, 
                                                                                       final Map<String, DataSourceParameter> newDataSourceParameters) {
        return Maps.filterEntries(newDataSourceParameters, input -> !oldDataSourceParameters.containsKey(input.getKey()));
    }
    
    private synchronized Map<String, DataSourceParameter> getModifiedDataSources(final Map<String, DataSourceParameter> oldDataSourceParameters, 
                                                                                 final Map<String, DataSourceParameter> newDataSourceParameters) {
        Map<String, DataSourceParameter> result = new LinkedHashMap<>();
        for (Entry<String, DataSourceParameter> entry : newDataSourceParameters.entrySet()) {
            if (isModifiedDataSource(oldDataSourceParameters, entry)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
    
    private synchronized boolean isModifiedDataSource(final Map<String, DataSourceParameter> oldDataSourceParameters, final Entry<String, DataSourceParameter> target) {
        return oldDataSourceParameters.containsKey(target.getKey()) && !oldDataSourceParameters.get(target.getKey()).equals(target.getValue());
    }
    
    private synchronized Map<String, DataSource> getNewDataSources(final Map<String, DataSource> oldDataSources, final List<String> deletedDataSources,
                                                                   final Map<String, DataSourceParameter> addedDataSources, final Map<String, DataSourceParameter> modifiedDataSources) {
        Map<String, DataSource> result = new LinkedHashMap<>(oldDataSources);
        result.keySet().removeAll(deletedDataSources);
        result.keySet().removeAll(modifiedDataSources.keySet());
        result.putAll(createDataSources(modifiedDataSources));
        result.putAll(createDataSources(addedDataSources));
        return result;
    }
    
    public final class JDBCBackendDataSource implements BackendDataSource {
    
        /**
         * Get connection.
         *
         * @param schemaName scheme name
         * @param dataSourceName data source name
         * @return connection
         * @throws SQLException SQL exception
         */
        public Connection getConnection(final String schemaName, final String dataSourceName) throws SQLException {
            return getConnections(schemaName, dataSourceName, 1, ConnectionMode.MEMORY_STRICTLY).get(0);
        }
    
        /**
         * Get connections.
         *
         * @param schemaName scheme name
         * @param dataSourceName data source name
         * @param connectionSize size of connections to get
         * @param connectionMode connection mode
         * @return connections
         * @throws SQLException SQL exception
         */
        public List<Connection> getConnections(final String schemaName, final String dataSourceName, final int connectionSize, final ConnectionMode connectionMode) throws SQLException {
            return getConnections(schemaName, dataSourceName, connectionSize, connectionMode, TransactionType.LOCAL);
        }
    
        /**
         * Get connections.
         *
         * @param schemaName scheme name
         * @param dataSourceName data source name
         * @param connectionSize size of connections to be get
         * @param connectionMode connection mode
         * @param transactionType transaction type
         * @return connections
         * @throws SQLException SQL exception
         */
        @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
        public List<Connection> getConnections(final String schemaName, final String dataSourceName, 
                                               final int connectionSize, final ConnectionMode connectionMode, final TransactionType transactionType) throws SQLException {
            DataSource dataSource = schemaContexts.getSchemaContexts().get(schemaName).getSchema().getDataSources().get(dataSourceName);
            if (1 == connectionSize) {
                return Collections.singletonList(createConnection(schemaName, dataSourceName, dataSource, transactionType));
            }
            if (ConnectionMode.CONNECTION_STRICTLY == connectionMode) {
                return createConnections(schemaName, dataSourceName, dataSource, connectionSize, transactionType);
            }
            synchronized (dataSource) {
                return createConnections(schemaName, dataSourceName, dataSource, connectionSize, transactionType);
            }
        }
    
        private List<Connection> createConnections(final String schemaName, final String dataSourceName, 
                                                   final DataSource dataSource, final int connectionSize, final TransactionType transactionType) throws SQLException {
            List<Connection> result = new ArrayList<>(connectionSize);
            for (int i = 0; i < connectionSize; i++) {
                try {
                    result.add(createConnection(schemaName, dataSourceName, dataSource, transactionType));
                } catch (final SQLException ex) {
                    for (Connection each : result) {
                        each.close();
                    }
                    throw new SQLException(String.format("Could't get %d connections one time, partition succeed connection(%d) have released!", connectionSize, result.size()), ex);
                }
            }
            return result;
        }
    
        private Connection createConnection(final String schemaName, final String dataSourceName, final DataSource dataSource, final TransactionType transactionType) throws SQLException {
            ShardingTransactionManager shardingTransactionManager = 
                    schemaContexts.getSchemaContexts().get(schemaName).getRuntimeContext().getTransactionManagerEngine().getTransactionManager(transactionType);
            return isInShardingTransaction(shardingTransactionManager) ? shardingTransactionManager.getConnection(dataSourceName) : dataSource.getConnection();
        }
    
        private boolean isInShardingTransaction(final ShardingTransactionManager shardingTransactionManager) {
            return null != shardingTransactionManager && shardingTransactionManager.isInTransaction();
        }
    }
}
