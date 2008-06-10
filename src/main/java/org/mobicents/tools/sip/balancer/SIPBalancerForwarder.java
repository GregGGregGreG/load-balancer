package org.mobicents.tools.sip.balancer;

import gov.nist.javax.sip.header.CSeq;
import gov.nist.javax.sip.header.CallID;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.ListeningPoint;
import javax.sip.PeerUnavailableException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.HeaderFactory;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;



/**
 * A stateless UDP Forwarder that listens at a port and forwards to multiple
 * outbound addresses. It keeps a timer thread around that pings the list of
 * proxy servers and sends to the first proxy server.
 * 
 * @author M. Ranganathan
 * @author baranowb 
 */
public class SIPBalancerForwarder implements SipListener {
	private static Logger logger = Logger.getLogger(SIPBalancerForwarder.class
			.getCanonicalName());
	
	private SipProvider internalSipProvider;

    private SipProvider externalSipProvider;

    private String myHost;

    private int myPort;

    private int myExternalPort;

    private AddressFactory addressFactory;

    private MessageFactory messageFactory;

    private HeaderFactory headerFactory;

    private SipStack sipStack;
	
	
	private NodeRegister register;
    
    private static final HashSet<String> dialogCreatioMethods=new HashSet<String>(2);
    
    static{
    	dialogCreatioMethods.add(Request.INVITE);
    	dialogCreatioMethods.add(Request.SUBSCRIBE);
    }
	
	public SIPBalancerForwarder(String myHost, int myPort, int myExternalPort, NodeRegister register) throws IllegalStateException{
		super();
		this.myHost = myHost;
		this.myPort = myPort;
		this.myExternalPort = myExternalPort;
		this.register=register;		
	}

	public void start() throws IllegalStateException {
		
		SipFactory sipFactory = null;
        sipStack = null;
        
        Properties properties = new Properties();
        properties.setProperty("javax.sip.RETRANSMISSION_FILTER", "true");
        properties.setProperty("javax.sip.STACK_NAME", "SipBalancerForwarder");
        properties.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "off");
        // You need 16 for logging traces. 32 for debug + traces.
        // Your code will limp at 32 but it is best for debugging.
        properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "32");
        properties.setProperty("gov.nist.javax.sip.DEBUG_LOG",
                "logs/sipbalancerforwarderdebug.txt");
        properties.setProperty("gov.nist.javax.sip.SERVER_LOG", "logs/sipbalancerforwarder.xml");
        properties.setProperty("javax.sip.ROUTER_PATH", "org.mobicents.tools.sip.balancer.RouterImpl");
        properties.setProperty("javax.sip.OUTBOUND_PROXY", Integer
                .toString(myPort));

        try {
            // Create SipStack object
        	sipFactory = SipFactory.getInstance();
	        sipFactory.setPathName("gov.nist");
            sipStack = sipFactory.createSipStack(properties);
           
        } catch (PeerUnavailableException pue) {
            // could not find
            // gov.nist.jain.protocol.ip.sip.SipStackImpl
            // in the classpath
            throw new IllegalStateException("Cant create stack due to["+pue.getMessage()+"]", pue);
        }

        try {
            headerFactory = sipFactory.createHeaderFactory();
            addressFactory = sipFactory.createAddressFactory();
            messageFactory = sipFactory.createMessageFactory();

            ListeningPoint lp = sipStack.createListeningPoint(myHost, myPort, "udp");
            internalSipProvider = sipStack.createSipProvider(lp);
            internalSipProvider.addSipListener(this);

            lp = sipStack.createListeningPoint(myHost, myExternalPort, "udp");
            externalSipProvider = sipStack.createSipProvider(lp);
            externalSipProvider.addSipListener(this);

            sipStack.start();
        } catch (Exception ex) {
        	throw new IllegalStateException("Cant create sip objects and lps due to["+ex.getMessage()+"]", ex);
        }
        logger.info("Sip Balancer started on address " + myHost + ", external port : " + myExternalPort + ", port : "+ myPort);
	}
	
	public void stop() {
		Iterator<SipProvider> sipProviderIterator = sipStack.getSipProviders();
		try{
			while (sipProviderIterator.hasNext()) {
				SipProvider sipProvider = sipProviderIterator.next();
				ListeningPoint[] listeningPoints = sipProvider.getListeningPoints();
				for (ListeningPoint listeningPoint : listeningPoints) {
					logger.info("Removing the following Listening Point " + listeningPoint);
					sipProvider.removeListeningPoint(listeningPoint);
					sipStack.deleteListeningPoint(listeningPoint);
				}
				logger.info("Removing the sip provider");
				sipProvider.removeSipListener(this);	
				sipStack.deleteSipProvider(sipProvider);			
			}
		} catch (Exception e) {
			throw new IllegalStateException("Cant remove the listening points or sip providers", e);
		}
		
		sipStack.stop();
		sipStack = null;
		logger.info("Sip Balancer stopped");
	}
	
	public void processDialogTerminated(
			DialogTerminatedEvent dialogTerminatedEvent) {
		// We wont see those
	}

	public void processIOException(IOExceptionEvent exceptionEvent) {
		// Hopefully we wont see those either
	}

	public void processRequest(RequestEvent requestEvent) {
		// This will be invoked only by external endpoint
		
		try {
            SipProvider sipProvider = (SipProvider) requestEvent.getSource();
            Request request = requestEvent.getRequest();
            if (sipProvider == this.externalSipProvider) {
                if(logger.isLoggable(Level.FINEST)) {
                	logger.finest("GOT EXTERNAL REQUEST:\n"+request);
                }
                //if(request.getMethod().equals(Request.BYE))
                //	return;
                // Tack our own via header to the request.
                // Tack on our internal port so the other side responds to me.
                ViaHeader viaHeader = headerFactory.createViaHeader(
                        this.myHost, this.myPort, "udp", "z9hG4bK"+Math.random()*31+""+System.currentTimeMillis());
                // Add the via header to the top of the header list.
                request.addHeader(viaHeader);
                RouteHeader routeHeader = (RouteHeader) request.getHeader(RouteHeader.NAME);
                SipURI routeUri = (SipURI)routeHeader.getAddress().getURI();
                //FIXME check against a list of host we may have
                if(routeUri.getHost().equalsIgnoreCase(myHost) && routeUri.getPort() == myExternalPort) {
                	if(logger.isLoggable(Level.FINEST)) {
                		logger.finest("this orute header is for us removing it " + routeUri);
                	}
                	request.removeFirst(RouteHeader.NAME);
                }
                
                // Record route the invite so the bye comes to me. FIXME: Add check, on reINVITE we wont add ourselvses twice
                if (request.getMethod().equals(Request.INVITE) || request.getMethod().equals(Request.SUBSCRIBE)) {
                    SipURI sipUri = this.addressFactory
                            .createSipURI(null, sipProvider.getListeningPoint(
                                    "udp").getIPAddress());
                    sipUri.setPort(sipProvider.getListeningPoint("udp")
                            .getPort());
                    Address address = this.addressFactory.createAddress(sipUri);
                    address.setURI(sipUri);
                    if(logger.isLoggable(Level.FINEST)) {
                    	logger.finest("ADDING RRH:"+address);
                    }
//                    sipUri.setLrParam();
                    RecordRouteHeader recordRoute = headerFactory
                            .createRecordRouteHeader(address);                    
                    request.addHeader(recordRoute);
                } /* else if ( request.getMethod().equals(Request.BYE)) {
                    // Should have me on the route list.
                    request.removeFirst(RouteHeader.NAME);
                } */

                if(logger.isLoggable(Level.FINEST)) {
                	logger.finest("SENDING TO INTERNAL:"+request);
                }
            
                this.internalSipProvider.sendRequest(request);

            } else {
            	//We wont see any reqs from this side, we will only see responses
            	//Proxy sets RR to IP_ExternalPort of LB
            	throw new IllegalStateException("Illegal state!! unexpected request");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
	}

	public void processResponse(ResponseEvent responseEvent) {
		try {
            SipProvider sipProvider = (SipProvider) responseEvent.getSource();
            Response response = responseEvent.getResponse();
            SipProvider sender=null;
            
            int status=response.getStatusCode();
            String method=((CSeq)response.getHeader(CSeq.NAME)).getMethod();
            String callID=((CallID)response.getHeader(CallID.NAME)).getCallId();
            if (sipProvider == this.internalSipProvider) {
            	if(logger.isLoggable(Level.FINEST)) {
            		logger.finest("GOT RESPONSE INTERNAL:\n"+response);
            	}
            	 // Topmost via header is me. As it is reposne to external reqeust
                response.removeFirst(ViaHeader.NAME);
            	
                sender=this.externalSipProvider;
                
                //Here if we get response other than 100-2xx we have to clean register from this session
                if(dialogCreatioMethods.contains(method) && !(100<=status && status<300)) 
                {
                	register.unStickSessionFromNode(callID);
                }
            } else {
                //Topmost via header is proxy, we leave it
            	//This happens as proxy sets RR to external interface, but it sets Via to itself.
            	if(logger.isLoggable(Level.FINEST)) {
            		logger.finest("GOT RESPONSE INTERNAL, FOR UAS REQ:\n"+response);
            	}
            	sender=this.internalSipProvider;
            	
            	//Here we should care only for BYE, all other are send without any change
            	//We dont even bother status, as BYE means that UAS wants to terminate.
            	if(method.equals(Request.BYE))
            	{
            		register.unStickSessionFromNode(callID);
            	}
            }
            sender.sendResponse(response);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
	}

	public void processTimeout(TimeoutEvent timeoutEvent) {
		// TODO Auto-generated method stub
	}

	public void processTransactionTerminated(
			TransactionTerminatedEvent transactionTerminatedEvent) {
		// TODO Auto-generated method stub
	}
}
