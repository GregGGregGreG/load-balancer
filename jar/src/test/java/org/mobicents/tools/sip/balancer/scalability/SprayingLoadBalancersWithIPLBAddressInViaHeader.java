/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.tools.sip.balancer.scalability;

import java.util.Properties;

import javax.sip.SipException;
import javax.sip.address.SipURI;
import javax.sip.header.RecordRouteHeader;

import junit.framework.TestCase;

import org.mobicents.tools.sip.balancer.AppServer;
import org.mobicents.tools.sip.balancer.BalancerRunner;
import org.mobicents.tools.sip.balancer.EventListener;
import org.mobicents.tools.sip.balancer.HeaderConsistentHashBalancerAlgorithm;
import org.mobicents.tools.sip.balancer.UDPPacketForwarder;
import org.mobicents.tools.sip.balancer.operation.Shootist;

public class SprayingLoadBalancersWithIPLBAddressInViaHeader extends TestCase {
	int numBalancers = 4;
	BalancerRunner[] balancers = new BalancerRunner[numBalancers];
	int numNodes = 10;
	AppServer[] servers = new AppServer[numNodes];
	Shootist shootist;

	UDPPacketForwarder externalIpLoadBalancer;
	UDPPacketForwarder internalIpLoadBalancer;
	
	private BalancerRunner prepBalancer(String id) {
		BalancerRunner balancer = new BalancerRunner();
		Properties properties = new Properties();
		properties.setProperty("javax.sip.STACK_NAME", "SipBalancerForwarder" + id);
		properties.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "off");
		// You need 16 for logging traces. 32 for debug + traces.
		// Your code will limp at 32 but it is best for debugging.
		properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "LOG4J");
		properties.setProperty("gov.nist.javax.sip.DEBUG_LOG",
				"logs/sipbalancerforwarderdebug.txt");
		properties.setProperty("gov.nist.javax.sip.SERVER_LOG",
				"logs/sipbalancerforwarder.xml");
		properties.setProperty("gov.nist.javax.sip.THREAD_POOL_SIZE", "2");
		properties.setProperty("gov.nist.javax.sip.REENTRANT_LISTENER", "true");
		properties.setProperty("gov.nist.javax.sip.CANCEL_CLIENT_TRANSACTION_CHECKED", "false");
		
		properties.setProperty("useIpLoadBalancerAddressInViaHeaders", "true");
		properties.setProperty("externalHost", "127.0.0.1");
		properties.setProperty("internalHost", "127.0.0.1");
		properties.setProperty("internalPort", "5"+id+"65");
		properties.setProperty("externalPort", "5"+id+"60");
		properties.setProperty("rmiRegistryPort", "2" + id +"00");
		properties.setProperty("httpPort", "2" + id +"80");
		properties.setProperty("jmxHtmlAdapterPort", "8" + id +"00");
		properties.setProperty("algorithmClass", HeaderConsistentHashBalancerAlgorithm.class.getName());
		properties.setProperty("externalIpLoadBalancerAddress", "127.0.0.1");
		properties.setProperty("externalIpLoadBalancerPort", "9988");
		properties.setProperty("internalIpLoadBalancerAddress", "127.0.0.1");
		properties.setProperty("internalIpLoadBalancerPort", "9922");
		balancer.start(properties);
		return balancer;
	}
	
	protected void setUp() throws Exception {
		super.setUp();
		shootist = new Shootist();
		String balancerString = "";
		String externalIpLBString = "";
		String internalIpLBString = "";
		for(Integer q=0;q<numBalancers;q++) {
			balancers[q] = prepBalancer(q.toString());
			balancerString += "127.0.0.1:"+2+q+"00,";
			externalIpLBString += "127.0.0.1:"+5+q+"60,";
			internalIpLBString += "127.0.0.1:"+5+q+"65,";
		}
		for(int q=0;q<servers.length;q++) {
			servers[q] = new AppServer("node" + q,4060+q);
			servers[q].start();
			servers[q].setBalancers(balancerString);
		}
		
		externalIpLoadBalancer = new UDPPacketForwarder(9988, externalIpLBString, "127.0.0.1");
		externalIpLoadBalancer.start();
		internalIpLoadBalancer = new UDPPacketForwarder(9922, internalIpLBString, "127.0.0.1");
		internalIpLoadBalancer.start();
		Thread.sleep(5000);
	}
	/* (non-Javadoc)
	 * @see junit.framework.TestCase#tearDown()
	 */
	protected void tearDown() throws Exception {
		super.tearDown();
		externalIpLoadBalancer.stop();
		internalIpLoadBalancer.stop();
		for(int q=0;q<servers.length;q++) {
			servers[q].stop();
		}
		shootist.stop();
		for(int q=0;q<numBalancers;q++) {
			balancers[q].stop();
		}
	}
	
	AppServer inviteServer,ackServer,byeServer;

	public void testSprayingRoundRobinSIPLBsUASCallConsistentHash() throws Exception {
		EventListener failureEventListener = new EventListener() {
			
			@Override
			public void uasAfterResponse(int statusCode, AppServer source) {
				
				
			}
			
			@Override
			public void uasAfterRequestReceived(String method, AppServer source) {
				if(method.equals("INVITE")) {
					inviteServer = source;
				} else if(method.equals("ACK")) {
					ackServer = source;
				} else {
					byeServer = source;
				}
			}

			@Override
			public void uacAfterRequestSent(String method, AppServer source) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void uacAfterResponse(int statusCode, AppServer source) {
		
			}
		};
		for(AppServer as:servers) as.setEventListener(failureEventListener);
		shootist.peerHostPort="127.0.0.1:9988";
		shootist.callerSendsBye=true;
		shootist.sendInitialInvite();
		//servers[0].sendHeartbeat = false;
		Thread.sleep(12000);
		shootist.sendBye();
		Thread.sleep(2000);

		assertEquals(3, externalIpLoadBalancer.sipMessageWithoutRetrans.size());
		assertSame(inviteServer, byeServer);
		assertSame(inviteServer, ackServer);
		assertNotNull(byeServer);
		assertNotNull(ackServer);
	}
	AppServer ringingAppServer;
	AppServer okAppServer;
	public void testASactingAsUAC() throws Exception {
		
		EventListener failureEventListener = new EventListener() {
			
			@Override
			public void uasAfterResponse(int statusCode, AppServer source) {
				
				
			}
			
			@Override
			public void uasAfterRequestReceived(String method, AppServer source) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void uacAfterRequestSent(String method, AppServer source) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void uacAfterResponse(int statusCode, AppServer source) {
				if(statusCode == 180) {
					ringingAppServer = source;
					source.sendCleanShutdownToBalancers();		
				} else {
					okAppServer = source;
					
				}
			}
		};
		for(AppServer as:servers) as.setEventListener(failureEventListener);
		shootist.callerSendsBye = true;
		
		String fromName = "sender";
		String fromHost = "sip-servlets.com";
		SipURI fromAddress = servers[0].protocolObjects.addressFactory.createSipURI(
				fromName, fromHost);
				
		String toUser = "replaces";
		String toHost = "sip-servlets.com";
		SipURI toAddress = servers[0].protocolObjects.addressFactory.createSipURI(
				toUser, toHost);
		
		SipURI ruri = servers[0].protocolObjects.addressFactory.createSipURI(
				"usera", "127.0.0.1:5033");
		ruri.setLrParam();
		SipURI route = servers[0].protocolObjects.addressFactory.createSipURI(
				"lbaddress_noInternalPort", "127.0.0.1:5065");
		route.setParameter("node_host", "127.0.0.1");
		route.setParameter("node_port", "4060");
		route.setLrParam();
		shootist.start();
		//servers[0].sipListener.sendSipRequest("INVITE", fromAddress, toAddress, null, null, false);
		servers[0].sipListener.sendSipRequest("INVITE", fromAddress, toAddress, null, route, false, null, null, ruri);
		Thread.sleep(16000);
		assertTrue(shootist.inviteRequest.getHeader(RecordRouteHeader.NAME).toString().contains("node_host"));
		assertNotSame(ringingAppServer, okAppServer);
		assertNotNull(ringingAppServer);
		assertNotNull(okAppServer);
	}
	public void testSprayingMultipleIndialogMessages() throws Exception {
		shootist.callerSendsBye=true;
		shootist.sendInitialInvite();
		Thread.sleep(8000);
		for(int q=0;q<10;q++){
		shootist.sendMessage();Thread.sleep(600);
		}
		Thread.sleep(600);
		assertTrue(shootist.responses.size()>10);
	}

}
