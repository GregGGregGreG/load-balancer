package org.mobicents.tools.sip.balancer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import javax.sip.SipProvider;

public class BlackholeAppServer {
	public ProtocolObjects protocolObjects;
	public TestSipListener sipListener;
	Timer timer;
	int port;
	String name;
	SIPNode appServerNode;
	public boolean sendHeartbeat = true;
	String lbAddress;
	int lbRMIport;
	int lbSIPext;
	int lbSIPint;
	public SipProvider sipProvider;

	public BlackholeAppServer(String appServer, int port, String lbAddress, int lbRMI, int lbSIPext, int lbSIPint) {
		this.port = port;
		this.name = appServer;
		this.lbAddress = lbAddress;
		this.lbRMIport = lbRMI;
		this.lbSIPext = lbSIPext;
		this.lbSIPint = lbSIPint;
	}
	
	public BlackholeAppServer(String appServer, int port) {
		this(appServer, port, "127.0.0.1");

	} 
	
	public BlackholeAppServer(String appServer, int port, String address) {
		this(appServer, port, address, 2000, 5060, 5065);

	} 
	
	public void setEventListener(EventListener listener) {
		sipListener.eventListener = listener;
	}
	ServerSocket tcpSocket;
	DatagramSocket socket;
	public long numUnitsReceived;
	DatagramPacket packet = new DatagramPacket(new byte[1000], 1000);
	byte[] temp = new byte[10000];
	Thread thread;
	Thread tcpThread;
	String lastString = "";
	public void start() {
		timer = new Timer();
		try {
			socket = new DatagramSocket(port, InetAddress.getByName(lbAddress));
			try {
				tcpSocket = new ServerSocket(port);
				tcpThread = new Thread() {
					public void run() {
						while(true) {
							
							try {
								final Socket sock = tcpSocket.accept();
								new Thread() {
									public void run() {
										while(true) {
											try {
												numUnitsReceived+=sock.getInputStream().read(temp);
											} catch (IOException e) {
												return;
											}
										}
									}
								}.start();
								
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
					}
				};
				tcpThread.start();
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			thread = new Thread() {
				public void run() {
					try {
						while(true) {
							socket.receive(packet);
							numUnitsReceived++;
						}
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			};
			thread.start();
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		appServerNode = new SIPNode(name, "127.0.0.1");
		appServerNode.getProperties().put("udpPort", port);
		appServerNode.getProperties().put("tcpPort", port);
		timer.schedule(new TimerTask() {
			
			@Override
			public void run() {
				ArrayList<SIPNode> nodes = new ArrayList<SIPNode>();
				nodes.add(appServerNode);
				sendKeepAliveToBalancers(nodes);
			}
		}, 1000, 1000);
	}
	
	public void stop() {
		try {
			thread.interrupt();
			thread.stop();
		} catch (Exception e) {}
		try {
			tcpThread.interrupt();
			tcpThread.stop();
		} catch (Exception e) {}
		try {
			tcpSocket.close();
		} catch (IOException e) {
		}
		timer.cancel();
		socket.close();
		if(protocolObjects != null)
			protocolObjects.sipStack.stop();
		protocolObjects=null;
		//sendCleanShutdownToBalancers();
	}

	private void sendKeepAliveToBalancers(ArrayList<SIPNode> info) {
		if(sendHeartbeat) {
			Thread.currentThread().setContextClassLoader(NodeRegisterRMIStub.class.getClassLoader());
			try {
				Registry registry = LocateRegistry.getRegistry(lbAddress, lbRMIport);
				NodeRegisterRMIStub reg=(NodeRegisterRMIStub) registry.lookup("SIPBalancer");
				reg.handlePing(info);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}	
	public void sendCleanShutdownToBalancers() {
		ArrayList<SIPNode> nodes = new ArrayList<SIPNode>();
		nodes.add(appServerNode);
		sendCleanShutdownToBalancers(nodes);
	}
	
	public void sendCleanShutdownToBalancers(ArrayList<SIPNode> info) {
		Thread.currentThread().setContextClassLoader(NodeRegisterRMIStub.class.getClassLoader());
		try {
			Registry registry = LocateRegistry.getRegistry(lbAddress, lbRMIport);
			NodeRegisterRMIStub reg=(NodeRegisterRMIStub) registry.lookup("SIPBalancer");
			reg.forceRemoval(info);
			stop();
			Thread.sleep(2000); // delay the OK for a while
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public TestSipListener getTestSipListener() {
		return this.sipListener;
	}

}
