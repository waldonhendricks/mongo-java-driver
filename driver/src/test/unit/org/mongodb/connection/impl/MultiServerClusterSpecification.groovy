/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */





package org.mongodb.connection.impl

import org.mongodb.connection.ChangeEvent
import org.mongodb.connection.ChangeListener
import org.mongodb.connection.Cluster
import org.mongodb.connection.ClusterDescription
import org.mongodb.connection.ClusterSettings
import org.mongodb.connection.ClusterType
import org.mongodb.connection.ServerAddress
import org.mongodb.connection.ServerDescription
import org.mongodb.connection.ServerType
import org.mongodb.connection.TestClusterableServerFactory
import org.mongodb.session.PrimaryServerSelector
import spock.lang.Specification

import static org.mongodb.connection.ClusterConnectionMode.Multiple
import static org.mongodb.connection.ClusterType.ReplicaSet
import static org.mongodb.connection.ClusterType.Sharded
import static org.mongodb.connection.ServerConnectionState.Connected
import static org.mongodb.connection.ServerConnectionState.Connecting
import static org.mongodb.connection.ServerType.ReplicaSetPrimary
import static org.mongodb.connection.ServerType.ReplicaSetSecondary
import static org.mongodb.connection.ServerType.ShardRouter
import static org.mongodb.connection.ServerType.StandAlone

class MultiServerClusterSpecification extends Specification {
    private final ServerAddress firstServer = new ServerAddress('localhost:27017')
    private final ServerAddress secondServer = new ServerAddress('localhost:27018')
    private final ServerAddress thirdServer = new ServerAddress('localhost:27019')

    private final TestClusterableServerFactory factory = new TestClusterableServerFactory()

    def 'should correct report description when the cluster first starts'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().mode(Multiple).hosts([firstServer]).build(), factory)

        when:
        sendNotification(firstServer, ReplicaSetPrimary)

        then:
        cluster.description.isConnecting()
        cluster.description.type == ReplicaSet
        cluster.description.connectionMode == Multiple
    }

    def 'should not get server when closed'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().hosts(Arrays.asList(firstServer)).build(), factory)
        cluster.close()

        when:
        cluster.getServer(firstServer)

        then:
        thrown(IllegalStateException)
    }

    def 'should discover all servers in the cluster'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().mode(Multiple).hosts([firstServer]).build(), factory)

        when:
        sendNotification(firstServer, ReplicaSetPrimary)

        then:
        cluster.description.all == getDescriptions(firstServer, secondServer, thirdServer)
    }

    def 'should remove a server when it no longer appears in hosts'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().hosts([firstServer, secondServer, thirdServer]).build(), factory);
        sendNotification(firstServer, ReplicaSetPrimary)
        sendNotification(secondServer, ReplicaSetSecondary)
        sendNotification(thirdServer, ReplicaSetSecondary)

        when:
        sendNotification(firstServer, ReplicaSetPrimary, [firstServer, secondServer])

        then:
        cluster.description.all == getDescriptions(firstServer, secondServer)
    }

    def 'should fire change event on cluster change'() {
        given:
        ChangeEvent<ClusterDescription> changeEvent = null
        Cluster cluster = new MultiServerCluster(ClusterSettings.builder().mode(Multiple).hosts([firstServer]).build(), factory)
        cluster.addChangeListener(new ChangeListener<ClusterDescription>() {
            @Override
            void stateChanged(final ChangeEvent<ClusterDescription> event) {
                changeEvent = event
            }
        })

        when:
        sendNotification(firstServer, ReplicaSetPrimary)

        then:
        changeEvent != null
        changeEvent.oldValue != null
        changeEvent.oldValue.all.size() == 1
        changeEvent.newValue != null
        changeEvent.newValue.all.size() == 3
    }

    def 'should remove change listener'() {
        given:
        def changeEvent = null
        def cluster = new MultiServerCluster(ClusterSettings.builder().mode(Multiple).hosts([firstServer]).build(), factory)
        def listener = new ChangeListener<ClusterDescription>() {
            @Override
            void stateChanged(final ChangeEvent<ClusterDescription> event) {
                changeEvent = event
            }
        }
        cluster.addChangeListener(listener)
        cluster.removeChangeListener(listener)

        when:
        sendNotification(firstServer, ReplicaSetPrimary)

        then:
        changeEvent == null
    }

    def 'should remove a server of the wrong type when type is replica set'() {
        given:
        def cluster = new MultiServerCluster(
                ClusterSettings.builder().requiredClusterType(ReplicaSet).hosts([firstServer, secondServer]).build(), factory)

        when:
        sendNotification(secondServer, ShardRouter)

        then:
        cluster.description.type == ReplicaSet
        cluster.description.all == getDescriptions(firstServer)
    }

    def 'should remove a server of the wrong type when type is sharded'() {
        given:
        def cluster = new MultiServerCluster(
                ClusterSettings.builder().requiredClusterType(Sharded).hosts([firstServer, secondServer]).build(), factory)
        sendNotification(firstServer, ShardRouter)

        when:
        sendNotification(secondServer, ReplicaSetPrimary)

        then:
        cluster.description.type == Sharded
        cluster.description.all == getDescriptions(firstServer)
    }

    def 'should remove a server of wrong type from discovered replica set'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().mode(Multiple).hosts([firstServer, secondServer]).build(), factory)
        sendNotification(firstServer, ReplicaSetPrimary)

        when:
        sendNotification(secondServer, StandAlone)

        then:
        cluster.description.type == ReplicaSet
        cluster.description.all == getDescriptions(firstServer, thirdServer)
    }

    def 'should invalidate existing primary when a new primary notifies'() {
        given:
        new MultiServerCluster(ClusterSettings.builder().hosts([firstServer, secondServer]).build(), factory)
        sendNotification(firstServer, ReplicaSetPrimary)

        when:
        sendNotification(secondServer, ReplicaSetPrimary)

        then:
        getDescription(firstServer).state == Connecting
    }

    def 'should remove a server when a server in the seed list is not in hosts list, it should be removed'() {
        given:
        def serverAddressAlias = new ServerAddress('alternate')
        def cluster = new MultiServerCluster(ClusterSettings.builder().mode(Multiple).hosts([serverAddressAlias]).build(), factory)

        when:
        sendNotification(serverAddressAlias, ReplicaSetPrimary)

        then:
        cluster.description.all == getDescriptions(firstServer, secondServer, thirdServer)
    }

    def 'should retain a Standalone server given a hosts list of size 1'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().mode(Multiple).hosts([firstServer]).build(), factory)

        when:
        sendNotification(firstServer, StandAlone)

        then:
        cluster.description.type == ClusterType.StandAlone
        cluster.description.all == getDescriptions(firstServer)
    }

    def 'should remove any Standalone server given a hosts list of size greater than one'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().hosts([firstServer, secondServer]).build(), factory)

        when:
        sendNotification(firstServer, StandAlone)
        // necessary so that getting description doesn't block
        sendNotification(secondServer, ReplicaSetPrimary, [secondServer, thirdServer])

        then:
        !(getDescription(firstServer) in cluster.description.all)
        cluster.description.type == ReplicaSet
    }

    def 'should remove a member whose replica set name does not match the required one'() {
        given:
        def cluster = new MultiServerCluster(
                ClusterSettings.builder().hosts([secondServer]).requiredReplicaSetName('test1').build(), factory)
        when:
        sendNotification(secondServer, ReplicaSetPrimary, [firstServer, secondServer, thirdServer], 'test2')

        then:
        cluster.description.type == ReplicaSet
        cluster.description.all == [] as Set
    }

    def 'should throw from getServer if cluster is closed'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().hosts([firstServer]).build(), factory)
        cluster.close()

        when:
        cluster.getServer(new PrimaryServerSelector())

        then:
        thrown(IllegalStateException)
    }

    def 'should ignore a notification from a server that has been removed'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().hosts([firstServer, secondServer]).build(), factory)
        sendNotification(firstServer, ReplicaSetPrimary, [firstServer, thirdServer])

        when:
        sendNotification(secondServer, ReplicaSetSecondary, [secondServer])

        then:
        cluster.description.all == getDescriptions(firstServer, thirdServer)
    }

    def 'should ignore a notification from a server that is not ok'() {
        given:
        def cluster = new MultiServerCluster(ClusterSettings.builder().hosts([firstServer, secondServer]).build(), factory)
        sendNotification(firstServer, ReplicaSetPrimary, [firstServer, secondServer, thirdServer])

        when:
        sendNotification(secondServer, ReplicaSetSecondary, [], false)

        then:
        cluster.description.all == getDescriptions(firstServer, secondServer, thirdServer)
    }

    def sendNotification(ServerAddress serverAddress, ServerType serverType) {
        sendNotification(serverAddress, serverType, [firstServer, secondServer, thirdServer])
    }

    def sendNotification(ServerAddress serverAddress, ServerType serverType, List<ServerAddress> hosts) {
        sendNotification(serverAddress, serverType, hosts, null)
    }

    def sendNotification(ServerAddress serverAddress, ServerType serverType, List<ServerAddress> hosts, String requiredSetName) {
        factory.getServer(serverAddress).sendNotification(getBuilder(serverAddress, serverType, hosts, true, requiredSetName).build())
    }

    def sendNotification(ServerAddress serverAddress, ServerType serverType, List<ServerAddress> hosts, boolean ok) {
        factory.getServer(serverAddress).sendNotification(getBuilder(serverAddress, serverType, hosts, ok, null).build())
    }

    def getDescription(ServerAddress server) {
        factory.getServer(server).description
    }

    def getDescriptions(ServerAddress... servers) {
        servers.collect { factory.getServer(it).description } as Set
    }

    def getBuilder(ServerAddress serverAddress, ServerType serverType, List<ServerAddress> hosts, boolean ok, String requiredSetName) {
        ServerDescription.builder()
                .address(serverAddress)
                .type(serverType)
                .ok(ok)
                .state(Connected)
                .hosts(hosts*.toString() as Set)
                .setName(requiredSetName)
    }
}
