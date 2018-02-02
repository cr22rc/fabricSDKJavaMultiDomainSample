package org.cr22rc;
/*
 *
 *  Copyright 2017 IBM - All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.security.PrivateKey;
import java.security.Security;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.hyperledger.fabric.sdk.BlockEvent;
import org.hyperledger.fabric.sdk.BlockInfo;
import org.hyperledger.fabric.sdk.BlockchainInfo;
import org.hyperledger.fabric.sdk.ChaincodeEndorsementPolicy;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.Channel.PeerOptions;
import org.hyperledger.fabric.sdk.ChannelConfiguration;
import org.hyperledger.fabric.sdk.Enrollment;
import org.hyperledger.fabric.sdk.EventHub;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.InstallProposalRequest;
import org.hyperledger.fabric.sdk.InstantiateProposalRequest;
import org.hyperledger.fabric.sdk.Orderer;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.Peer.PeerRole;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.SDKUtils;
import org.hyperledger.fabric.sdk.TransactionInfo;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.TxReadWriteSetInfo;
import org.hyperledger.fabric.sdk.User;
import org.hyperledger.fabric.sdk.exception.TransactionEventException;
import org.hyperledger.fabric.sdk.exception.TransactionException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hyperledger.fabric.sdk.Channel.PeerOptions.createPeerOptions;

public class MultiDomainSample {

    private static final int TRANSACTION_WAIT_TIME = 60000 * 6;
    private static final String EXPECTED_EVENT_NAME = "event";
    private static final String CHAIN_CODE_NAME = "example_cc_go";
    private static final String CHAIN_CODE_PATH = "github.com/example_cc";
    private static final String CHAIN_CODE_VERSION = "1";
    private static final long PROPOSAL_WAIT_TIME = 60000 * 5;
    private static final byte[] EXPECTED_EVENT_DATA = "!".getBytes(UTF_8);
    private String testTxID;

    //
    private static final String ORG1MSP = "Org1MSP";
    private static final String ORG2MSP = "Org2MSP";

    public static void main(String[] args) throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        System.setProperty("org.hyperledger.fabric.sdk.channel.genesisblock_wait_time", 60000 * 15 + "");
        new MultiDomainSample().run(args);

    }

    private void run(String[] args) throws Exception {

        HFClient clientOrg1 = HFClient.createNewInstance();

        clientOrg1.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

        SampleUser peerAdminOrg1 = new SampleUser(ORG1MSP, "peerOrg1Admin");
        String certificate = new String(IOUtils.toByteArray(new FileInputStream(new File("src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/signcerts/Admin@org1.example.com-cert.pem"))), "UTF-8");

        PrivateKey privateKey = getPrivateKeyFromBytes(IOUtils.toByteArray(new FileInputStream("src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore/6b32e59640c594cf633ad8c64b5958ef7e5ba2a205cfeefd44a9e982ce624d93_sk")));
        peerAdminOrg1.setEnrollment(new SampleEnrollment(privateKey, certificate));
        clientOrg1.setUserContext(peerAdminOrg1);

        Orderer clientOrg1orderer = clientOrg1.newOrderer("orderer.example.com", "grpc://localhost:7050");

        Peer clientOrg1peerOrg1 = clientOrg1.newPeer("clientOrg1_peer0.org1.example.com", "grpc://localhost:7051");
        Peer clientOrg1peerOrg2 = clientOrg1.newPeer("clientOrg1_peer0.org2.example.com", "grpc://localhost:8051");
        EventHub clientOrg1eventHubOrg1 = clientOrg1.newEventHub("clientOrg1_peer0.org1.example.com", "grpc://localhost:7053");
        EventHub clientOrg1eventHubOrg2 = clientOrg1.newEventHub("clientOrg1_peer0.org2.example.com", "grpc://localhost:8053");

        Channel clientOrg1FooChannel = constructChannel("foo", clientOrg1, peerAdminOrg1, new LinkedList<>(Arrays.asList(new Orderer[] {clientOrg1orderer})),
                new LinkedList<>(Arrays.asList(new Peer[] {clientOrg1peerOrg1})),
                Collections.EMPTY_LIST, // no other peers to add at this point. Org2's has not joined the chain yet.!
                Collections.EMPTY_LIST, // use no event hubs.
                true); // first time create the channel.

        installChaincode(clientOrg1, new LinkedList<>(Arrays.asList(new Peer[] {clientOrg1peerOrg1}))); //install chaincode on org1's peer.

        clientOrg1FooChannel.shutdown(true); // done with this for now.  Need to have org2 set up their peer.

        // now again as the other org. this would usually be done in another application or instance in that organization

        HFClient clientOrg2 = HFClient.createNewInstance();

        clientOrg2.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

        SampleUser peerAdminOrg2 = new SampleUser(ORG2MSP, "peerOrg2Admin");
        String certificate1 = new String(IOUtils.toByteArray(new FileInputStream(new File("src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp/signcerts/Admin@org2.example.com-cert.pem"))), "UTF-8");

        PrivateKey privateKey1 = getPrivateKeyFromBytes(IOUtils.toByteArray(new FileInputStream("src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp/keystore/b2e2536de633960859d965f02b296083d1e8aa1e868016417c4e4fb760270b96_sk")));
        peerAdminOrg2.setEnrollment(new SampleEnrollment(privateKey1, certificate1));
        clientOrg2.setUserContext(peerAdminOrg2);

        Orderer clientOrg2orderer = clientOrg2.newOrderer("orderer.example.com", "grpc://localhost:7050");

        Peer clientOrg2peerOrg1 = clientOrg2.newPeer("clientOrg2_peer0.org1.example.com", "grpc://localhost:7051");
        Peer clientOrg2peerOrg2 = clientOrg2.newPeer("clientOrg2_peer0.org2.example.com", "grpc://localhost:8051");
       // EventHub clien10eventHubOrg2 = clientOrg1.newEventHub("clientOrg2_peer0.org2.example.com", "grpc://localhost:8053");

        Channel clientOrg2FooChannel = constructChannel("foo", clientOrg2, peerAdminOrg2, new LinkedList<>(Arrays.asList(new Orderer[] {clientOrg2orderer})),
                new LinkedList<>(Arrays.asList(new Peer[] {clientOrg2peerOrg2})), // join org2's peer.
                new LinkedList<>(Arrays.asList(new Peer[] {clientOrg2peerOrg1})), // just add org1's peer.
                Collections.EMPTY_LIST, // no event hubs.
                false); // no need to create channel was done before.

        //Install chaincode on org2's peer.
        installChaincode(clientOrg2, new LinkedList<>(Arrays.asList(new Peer[] {clientOrg2peerOrg2})));

        //Now that clientOrg2 org2 peer has joined, peerOrg2 we can register events on it.
        // Recreate the channel for org1

        clientOrg1orderer = clientOrg1.newOrderer("orderer.example.com", "grpc://localhost:7050");

        clientOrg1peerOrg1 = clientOrg1.newPeer("clientOrg1_peer0.org1.example.com", "grpc://localhost:7051");
        clientOrg1peerOrg2 = clientOrg1.newPeer("clientOrg1_peer0.org2.example.com", "grpc://localhost:8051");
       // clientOrg1eventHubOrg1 = clientOrg1.newEventHub("clientOrg1_peer0.org1.example.com", "grpc://localhost:7053");
       // clientOrg1eventHubOrg2 = clientOrg1.newEventHub("clientOrg1_peer0.org2.example.com", "grpc://localhost:8053");

        clientOrg1FooChannel = constructChannel("foo", clientOrg1, peerAdminOrg1, new LinkedList<>(Arrays.asList(new Orderer[] {clientOrg1orderer})),
                Collections.EMPTY_LIST, // no need to join peers. Org1's peer has already joined before.
                new LinkedList<>(Arrays.asList(new Peer[] {clientOrg1peerOrg1, clientOrg1peerOrg2})), //add both peers.
                Collections.EMPTY_LIST, // no event hubs.
                false); //no need to create channel.

        out("Running clientOrg2 for org2 channel");
        runChannel(clientOrg2, clientOrg2FooChannel, 0,
                true, // first time need to instantiate chaincode
                "300"); // Start value was 200. We move 100 expect 300

        out("Running clientOrg1 for org1 channel");
        runChannel(clientOrg1, clientOrg1FooChannel, 0, false, "400");

        //one more time for org2
        out("Running clientOrg2 for org2 channel second time.");
        runChannel(clientOrg2, clientOrg2FooChannel, 0, false, "500");

    }

    static PrivateKey getPrivateKeyFromBytes(byte[] data) throws IOException {
        final Reader pemReader = new StringReader(new String(data));

        final PrivateKeyInfo pemPair;
        try (PEMParser pemParser = new PEMParser(pemReader)) {
            pemPair = (PrivateKeyInfo) pemParser.readObject();
        }

        return new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getPrivateKey(pemPair);
    }

    void installChaincode(HFClient client, Collection<Peer> peersFromOrg) throws Exception {

        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();

        // Register a chaincode event listener that will trigger for any chaincode id and only for EXPECTED_EVENT_NAME event.
        ChaincodeID chaincodeID = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                .setVersion(CHAIN_CODE_VERSION)
                .setPath(CHAIN_CODE_PATH).build();

        InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
        installProposalRequest.setChaincodeID(chaincodeID);

        installProposalRequest.setChaincodeSourceLocation(new File("chaincode/gocc/sample1"));

        installProposalRequest.setChaincodeVersion(CHAIN_CODE_VERSION);

        out("Sending install proposal");

        ////////////////////////////
        // only a client from the same org as the peer can issue an install request
        int numInstallProposal = 0;

        numInstallProposal = numInstallProposal + peersFromOrg.size();
        Collection<ProposalResponse> responses = client.sendInstallProposal(installProposalRequest, peersFromOrg);

        for (ProposalResponse response : responses) {
            if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                out("Successful install proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                successful.add(response);
            } else {
                failed.add(response);
            }
        }

        out("Received %d install proposal responses. Successful+verified: %d . Failed: %d", numInstallProposal, successful.size(), failed.size());

        if (failed.size() > 0) {
            ProposalResponse first = failed.iterator().next();
            fail("Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
        }
    }

    private Channel constructChannel(String name, HFClient client, User peerAdmin, Collection<Orderer> orderers,
                                     Collection<Peer> jp, Collection<Peer> ap, Collection<EventHub> eh, boolean createChannel) throws Exception {
        ////////////////////////////
        //Construct the channel
        //

        out("Constructing channel %s", name);

        //Only peer Admin org
        client.setUserContext(peerAdmin);

        //Just pick the first orderer in the list to create the channel.

        Orderer anOrderer = orderers.iterator().next();
        orderers.remove(anOrderer);

        //Create channel that has only one signer that is this orgs peer admin. If channel creation policy needed more signature they would need to be added too.
        Channel newChannel = null;
        if (createChannel) {
            ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File("src/test/fixture/sdkintegration/e2e-2Orgs/channel/foo.tx"));
            newChannel = client.newChannel(name, anOrderer, channelConfiguration, client.getChannelConfigurationSignature(channelConfiguration, peerAdmin));
        } else {
            newChannel = client.newChannel(name);
            newChannel.addOrderer(anOrderer);
        }

        assert newChannel != null : "channel can not be null";

        out("Created channel %s", name);

        PeerOptions peerOptions = createPeerOptions();
        if (!eh.isEmpty()) {
            peerOptions.setPeerRoles(PeerRole.NO_EVENT_SOURCE); //use the event hubs
        }

        for (Peer peer : jp) {
            newChannel.joinPeer(peer, peerOptions);

        }

        for (Peer peer : ap) {

            //newChannel.addPeer(peer, Channel.PeerOptions.createPeerOptions().setPeerRoles(Peer.PeerRole.NO_EVENT_SOURCE));
            newChannel.addPeer(peer, peerOptions);
        }

        for (EventHub eventHub : eh) {
            newChannel.addEventHub(eventHub);
        }

        newChannel.initialize();

        out("Finished initialization channel %s", name);

        return newChannel;

    }

    void runChannel(HFClient client, Channel channel, int delta, final boolean instantiate, final String expect) {

        try {

            final String channelName = channel.getName();

            out("Running channel %s", channelName);

            Collection<Orderer> orderers = channel.getOrderers();
            final ChaincodeID chaincodeID;
            Collection<ProposalResponse> responses;
            Collection<ProposalResponse> successful = new LinkedList<>();
            Collection<ProposalResponse> failed = new LinkedList<>();

            chaincodeID = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                    .setVersion(CHAIN_CODE_VERSION)
                    .setPath(CHAIN_CODE_PATH).build();

            CompletableFuture<Boolean> booleanCompletableFuture;
            if (instantiate) {

                ///////////////
                //// Instantiate chaincode.
                InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
                instantiateProposalRequest.setProposalWaitTime(PROPOSAL_WAIT_TIME);
                instantiateProposalRequest.setChaincodeID(chaincodeID);
                instantiateProposalRequest.setFcn("init");
                instantiateProposalRequest.setArgs("a", "5000", "b",
                        "" + (200 + delta)); // start with 200
                Map<String, byte[]> tm = new HashMap<>();
                tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
                tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
                instantiateProposalRequest.setTransientMap(tm);

                ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
                //chaincodeEndorsementPolicy.fromYamlFile(new File("src/test/fixture/sdkintegration/chaincodeendorsementpolicy.yaml"));
                chaincodeEndorsementPolicy.fromYamlFile(new File("src/test/fixture/sdkintegration/chaincodeendorsementpolicyAllMembers.yaml"));
                instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);

                out("Sending instantiateProposalRequest to all peers with arguments: a and b set to 100 and %s respectively", "" + (200 + delta));
                successful.clear();
                failed.clear();

                responses = channel.sendInstantiationProposal(instantiateProposalRequest);

                for (ProposalResponse response : responses) {
                    if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                        successful.add(response);
                        out("Succesful instantiate proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                    } else {
                        failed.add(response);
                    }
                }
                out("Received %d instantiate proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size());
                if (failed.size() > 0) {
                    ProposalResponse first = failed.iterator().next();
                    fail("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
                }

                ///////////////
                /// Send instantiate transaction to orderer
                out("Sending instantiateTransaction to orderer with a and b set to 100 and %s respectively", "" + (200 + delta));
                booleanCompletableFuture = channel.sendTransaction(successful, orderers).thenApply(transactionEvent -> {
                    // assert (transactionEvent.isValid()); // must be valid to be here.

                    return transactionEvent.isValid() ? new CompletableFuture().complete(true) : new CompletableFuture().completeExceptionally(new TransactionException(""));
//                out("Finished instantiate transaction with transaction id %s", transactionEvent.getTransactionID());
                });
            } else {
                booleanCompletableFuture = new CompletableFuture(); // fake future on instantiate
                booleanCompletableFuture.complete(true);
            }

            //  CompletableFuture<BlockEvent.TransactionEvent> transactionEventCompletableFuture = channel.sendTransaction(successful, orderers).thenApply()
            booleanCompletableFuture.thenApply(notused -> {
                try {
                    successful.clear();
                    failed.clear();

                    ///////////////
                    /// Send transaction proposal to all peers
                    TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
                    transactionProposalRequest.setChaincodeID(chaincodeID);
                    transactionProposalRequest.setFcn("invoke");
                    transactionProposalRequest.setProposalWaitTime(PROPOSAL_WAIT_TIME);
                    transactionProposalRequest.setArgs("move", "a", "b", "100");

                    Map<String, byte[]> tm2 = new HashMap<>();
                    tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8)); //Just some extra junk in transient map
                    tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8)); // ditto
                    tm2.put("result", ":)".getBytes(UTF_8));  // This should be returned see chaincode why.
                    tm2.put(EXPECTED_EVENT_NAME, EXPECTED_EVENT_DATA);  //This should trigger an event see chaincode why.

                    transactionProposalRequest.setTransientMap(tm2);

                    out("sending transactionProposal to all peers with arguments: move(a,b,100)");

                    //            LinkedList<Peer> singlepeers = new LinkedList<>(Arrays.asList(new Peer[] {channel.getPeers().iterator().next()}));

                    Collection<ProposalResponse> transactionPropResp = channel.sendTransactionProposal(transactionProposalRequest, channel.getPeers());
                    // Collection<ProposalResponse> transactionPropResp = channel.sendTransactionProposal(transactionProposalRequest, singlepeers);
                    for (ProposalResponse response : transactionPropResp) {
                        if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                            out("Successful transaction proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                            successful.add(response);
                        } else {
                            failed.add(response);
                        }
                    }

                    // Check that all the proposals are consistent with each other. We should have only one set
                    // where all the proposals above are consistent.
                    Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);
                    if (proposalConsistencySets.size() != 1) {
                        fail(format("Expected only one set of consistent proposal responses but got %d", proposalConsistencySets.size()));
                    }

                    out("Received %d transaction proposal responses. Successful+verified: %d . Failed: %d",
                            transactionPropResp.size(), successful.size(), failed.size());
                    if (failed.size() > 0) {
                        ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
                        fail("Not enough endorsers for invoke(move a,b,100):" + failed.size() + " endorser error: " +
                                firstTransactionProposalResponse.getMessage() +
                                ". Was verified: " + firstTransactionProposalResponse.isVerified());
                    }
                    out("Successfully received transaction proposal responses.");

                    ProposalResponse resp = transactionPropResp.iterator().next();
                    byte[] x = resp.getChaincodeActionResponsePayload(); // This is the data returned by the chaincode.
                    String resultAsString = null;
                    if (x != null) {
                        resultAsString = new String(x, "UTF-8");
                    }
                    assert (":)".equals(resultAsString));

                    assert 200 == resp.getChaincodeActionResponseStatus(); //Chaincode's status.

                    TxReadWriteSetInfo readWriteSetInfo = resp.getChaincodeActionResponseReadWriteSetInfo();
                    //See blockwalker below how to transverse this
                    assert null != readWriteSetInfo;
                    assert (readWriteSetInfo.getNsRwsetCount() > 0);

                    ChaincodeID cid = resp.getChaincodeID();

                    ////////////////////////////
                    // Send Transaction Transaction to orderer
                    out("Sending chaincode transaction(move a,b,100) to orderer.");
                    return channel.sendTransaction(successful).get(TRANSACTION_WAIT_TIME, TimeUnit.SECONDS);

                } catch (Exception e) {
                    out("Caught an exception while invoking chaincode");
                    e.printStackTrace();
                    fail("Failed invoking chaincode with error : " + e.getMessage(), e);
                }

                return null;

            }).thenApply(transactionEvent -> {
                try {

                    assert (transactionEvent.isValid()); // must be valid to be here.
                    out("Finished transaction with transaction id %s", transactionEvent.getTransactionID());
                    testTxID = transactionEvent.getTransactionID(); // used in the channel queries later

                    ////////////////////////////
                    // Send Query Proposal to all peers
                    //
                    //  String expect = "" + (300 + delta);
                    out("Now query chaincode for the value of b.");
                    QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
                    queryByChaincodeRequest.setArgs("query", "b");
                    queryByChaincodeRequest.setFcn("invoke");
                    queryByChaincodeRequest.setChaincodeID(chaincodeID);

                    Map<String, byte[]> tm2 = new HashMap<>();
                    tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
                    tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
                    queryByChaincodeRequest.setTransientMap(tm2);

                    Collection<ProposalResponse> queryProposals = channel.queryByChaincode(queryByChaincodeRequest, channel.getPeers());
                    for (ProposalResponse proposalResponse : queryProposals) {
                        if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                            fail("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
                                    ". Messages: " + proposalResponse.getMessage()
                                    + ". Was verified : " + proposalResponse.isVerified());
                        } else {
                            String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                            out("Query payload of b from peer %s returned %s", proposalResponse.getPeer().getName(), payload);
                            assert expect.equals(payload) : String.format("Expected %s but got %s", expect, payload);
                        }
                    }

                    return null;
                } catch (Exception e) {
                    out("Caught exception while running query");
                    e.printStackTrace();
                    fail("Failed during chaincode query with error : " + e.getMessage(), e);
                }

                return null;
            }).exceptionally(e -> {
                if (e instanceof TransactionEventException) {
                    BlockEvent.TransactionEvent te = ((TransactionEventException) e).getTransactionEvent();
                    if (te != null) {
                        fail(format("Transaction with txid %s failed. %s", te.getTransactionID(), e.getMessage()), e);
                    }
                }
                fail(format("Test failed with %s exception %s", e.getClass().getName(), e.getMessage()), e);

                return null;
            }).get(TRANSACTION_WAIT_TIME, TimeUnit.SECONDS);

            // Channel queries

            // We can only send channel queries to peers that are in the same org as the SDK user context
            // Get the peers from the current org being used and pick one randomly to send the queries to.
            Collection<Peer> peerSet = channel.getPeers();
            //  Peer queryPeer = peerSet.iterator().next();
            //   out("Using peer %s for channel queries", queryPeer.getName());

            BlockchainInfo channelInfo = channel.queryBlockchainInfo();
            out("Channel info for : " + channelName);
            out("Channel height: " + channelInfo.getHeight());
            String chainCurrentHash = Hex.encodeHexString(channelInfo.getCurrentBlockHash());
            String chainPreviousHash = Hex.encodeHexString(channelInfo.getPreviousBlockHash());
            out("Chain current block hash: " + chainCurrentHash);
            out("Chainl previous block hash: " + chainPreviousHash);

            // Query by block number. Should return latest block, i.e. block number 2
            BlockInfo returnedBlock = channel.queryBlockByNumber(channelInfo.getHeight() - 1);
            String previousHash = Hex.encodeHexString(returnedBlock.getPreviousHash());
            out("queryBlockByNumber returned correct block with blockNumber " + returnedBlock.getBlockNumber()
                    + " \n previous_hash " + previousHash);
            //          assertEquals(channelInfo.getHeight() - 1, returnedBlock.getBlockNumber());
            //          assertEquals(chainPreviousHash, previousHash);

            // Query by block hash. Using latest block's previous hash so should return block number 1
            byte[] hashQuery = returnedBlock.getPreviousHash();
            returnedBlock = channel.queryBlockByHash(hashQuery);
            out("queryBlockByHash returned block with blockNumber " + returnedBlock.getBlockNumber());
            //           assertEquals(channelInfo.getHeight() - 2, returnedBlock.getBlockNumber());

            // Query block by TxID. Since it's the last TxID, should be block 2
            returnedBlock = channel.queryBlockByTransactionID(testTxID);
            out("queryBlockByTxID returned block with blockNumber " + returnedBlock.getBlockNumber());
            //         assertEquals(channelInfo.getHeight() - 1, returnedBlock.getBlockNumber());

            // query transaction by ID
            TransactionInfo txInfo = channel.queryTransactionByID(testTxID);
            out("QueryTransactionByID returned TransactionInfo: txID " + txInfo.getTransactionID()
                    + "\n     validation code " + txInfo.getValidationCode().getNumber());

            out("Running for Channel %s done", channelName);

        } catch (Exception e) {
            out("Caught an exception running channel %s", channel.getName());
            e.printStackTrace();
            fail("Test failed with error : " + e.getMessage(), e);
        }
    }

    private void fail(String s, Throwable t) {
        throw new RuntimeException(s, t);
    }

    private void fail(String s) {
        throw new RuntimeException(s);
    }
    //CHECKSTYLE.ON: Method length is 320 lines (max allowed is 150).

    private void printUser(User user) {
        out("User: %s, MSPID: %s\nEnrollment certificate:\n%s", user.getName(), user.getMspId(), user.getEnrollment().getCert());
    }

    static void out(String format, Object... args) {

        System.err.flush();
        System.out.flush();

        System.out.println(format(format, args));
        System.err.flush();
        System.out.flush();

    }

    static class SampleUser implements User {

        private String mspId;

        public void setEnrollment(Enrollment enrollment) {
            assert enrollment != null : "enrollement can not be null";

            this.enrollment = enrollment;
        }

        private Enrollment enrollment;

        public SampleUser(String mspId, String name) {
            this.mspId = mspId;
            this.name = name;
        }

        private String name;

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Set<String> getRoles() {
            return null;
        }

        @Override
        public String getAccount() {
            return null;
        }

        @Override
        public String getAffiliation() {
            return null;
        }

        @Override
        public Enrollment getEnrollment() {
            return enrollment;
        }

        @Override
        public String getMspId() {
            return mspId;
        }
    }

    private static class SampleEnrollment implements Enrollment {

        private PrivateKey key;
        private String cert;

        public SampleEnrollment(PrivateKey key, String cert) {
            this.key = key;
            this.cert = cert;
        }

        @Override
        public PrivateKey getKey() {
            return key;
        }

        @Override
        public String getCert() {
            return cert;
        }
    }

}