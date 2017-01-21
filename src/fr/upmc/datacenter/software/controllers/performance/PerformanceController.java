package fr.upmc.datacenter.software.controllers.performance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import fr.upmc.components.AbstractComponent;
import fr.upmc.components.cvm.AbstractCVM;
import fr.upmc.components.ports.PortI;
import fr.upmc.datacenter.connectors.ControlledDataConnector;
import fr.upmc.datacenter.data.interfaces.LogicalResourcesProviderPortsDataI;
import fr.upmc.datacenter.data.interfaces.PerformanceControllerPortsDataI;
import fr.upmc.datacenter.interfaces.ControlledDataRequiredI.ControlledPullI;
import fr.upmc.datacenter.providers.resources.exceptions.NoApplicationVMException;
import fr.upmc.datacenter.providers.resources.exceptions.NoCoreException;
import fr.upmc.datacenter.providers.resources.logical.AllocatedApplicationVM;
import fr.upmc.datacenter.providers.resources.logical.connectors.LogicalResourcesProviderServicesConnector;
import fr.upmc.datacenter.providers.resources.logical.interfaces.LogicalResourcesProviderCoreReleasingNotificationHandlerI;
import fr.upmc.datacenter.providers.resources.logical.interfaces.LogicalResourcesProviderCoreReleasingNotificationI;
import fr.upmc.datacenter.providers.resources.logical.interfaces.LogicalResourcesProviderServicesI;
import fr.upmc.datacenter.providers.resources.logical.ports.LogicalResourcesProviderCoreReleasingNotificationInboundPort;
import fr.upmc.datacenter.providers.resources.logical.ports.LogicalResourcesProviderServicesOutboundPort;
import fr.upmc.datacenter.software.connectors.ApplicationVMReleasingNotificationConnector;
import fr.upmc.datacenter.software.connectors.RequestSubmissionConnector;
import fr.upmc.datacenter.software.controllers.performance.interfaces.PerformanceControllerI;
import fr.upmc.datacenter.software.controllers.performance.interfaces.PerformanceControllerManagementI;
import fr.upmc.datacenter.software.controllers.performance.interfaces.PerformanceControllerServicesI;
import fr.upmc.datacenter.software.controllers.performance.ports.PerformanceControllerManagementInboundPort;
import fr.upmc.datacenter.software.controllers.performance.ports.PerformanceControllerServicesInboundPort;
import fr.upmc.datacenter.software.dispatcher.Dispatcher;
import fr.upmc.datacenter.software.dispatcher.connectors.DispatcherManagementConnector;
import fr.upmc.datacenter.software.dispatcher.interfaces.DispatcherDynamicStateDataConsumerI;
import fr.upmc.datacenter.software.dispatcher.interfaces.DispatcherDynamicStateI;
import fr.upmc.datacenter.software.dispatcher.interfaces.DispatcherManagementI;
import fr.upmc.datacenter.software.dispatcher.ports.DispatcherDynamicStateDataOutboundPort;
import fr.upmc.datacenter.software.dispatcher.ports.DispatcherManagementOutboundPort;
import fr.upmc.datacenter.software.dispatcher.statistics.ExponentialAverage;
import fr.upmc.datacenter.software.dispatcher.time.Duration;
import fr.upmc.datacenter.software.enumerations.Tag;
import fr.upmc.datacenter.software.interfaces.ApplicationVMReleasingNotificationHandlerI;
import fr.upmc.datacenter.software.interfaces.ApplicationVMReleasingNotificationI;
import fr.upmc.datacenter.software.ports.ApplicationVMReleasingNotificationInboundPort;
import fr.upmc.datacenter.software.ports.ApplicationVMReleasingNotificationOutboundPort;
import fr.upmc.datacenter.software.ports.RequestSubmissionOutboundPort;
import fr.upmc.datacenterclient.requestgenerator.RequestGenerator;
import fr.upmc.datacenterclient.requestgenerator.connectors.RequestGeneratorManagementConnector;
import fr.upmc.datacenterclient.requestgenerator.interfaces.RequestGeneratorManagementI;
import fr.upmc.datacenterclient.requestgenerator.ports.RequestGeneratorManagementOutboundPort;
import fr.upmc.nodes.ComponentDataNode;

public class PerformanceController 
extends 	AbstractComponent
implements 	PerformanceControllerI,
			PerformanceControllerManagementI,
			PerformanceControllerServicesI,
			DispatcherDynamicStateDataConsumerI,
			ApplicationVMReleasingNotificationHandlerI,
			LogicalResourcesProviderCoreReleasingNotificationHandlerI
{

	public static boolean MULTIAPPLICATION = false;
	public static int DISPATCHER_PUSHING_INTERVAL = 500; // ms
	public static int MINIMUM_CORES_BY_AVM = 1;
	public static int MAXIMUM_CORES_BY_AVM = 8;

	List<DispatcherDynamicStateI> logs;

	private static int ticking = 0;

	/**
	 * Compte le nombre d'AVM en attente de lib�ration par le {@link Dispatcher}. </br>
	 * Le but �tant de ne pas lib�rer toutes les AVM qui ne seraient pas encore � jour dans <code>adds</code> d� � la latence
	 * en le la demande de lib�ration et la lib�ration effective (l'AVM � terminer sa liste de t�ches). 
	 */

	private static int releasing = 0;

	public enum Branch {LOGICAL_RESOURCES_PROVIDERS, DISPATCHERS, REQUEST_GENERATORS, APPLICATION_VMS, PERFORMANCE_CONTROLLER}

	//AllocatedDispatcherDataStore adds;
	Dispatcher dsp;
	PerformanceControllerDynamicState pcds;

	boolean hasApplication = false;

	Map<String, DispatcherDynamicStateI> dspddsimap; 

	ComponentDataNode performanceController;

	public PerformanceController(
			String uri,
			String performanceControllerManagementInboundPortURI,
			String performanceControllerServicesInboundPortURI,
			String performanceControllerCoreReleasingNotificationInboundPortURI) throws Exception
	{
		super(3, 1);

		logs = new ArrayList<>();
//		adds = new AllocatedDispatcherDataStore();
		pcds = new PerformanceControllerDynamicState();
		//		allocatedDsps = new HashMap<>();
		dspddsimap = new HashMap<>();

		performanceController = new ComponentDataNode(uri)
				.addPort(performanceControllerManagementInboundPortURI)
				.addPort(performanceControllerServicesInboundPortURI)
				.addChild(new ComponentDataNode(Branch.LOGICAL_RESOURCES_PROVIDERS))
				.addChild(new ComponentDataNode(Branch.DISPATCHERS))
				.addChild(new ComponentDataNode(Branch.REQUEST_GENERATORS))
				.addChild(new ComponentDataNode(Branch.APPLICATION_VMS));

		if ( !offeredInterfaces.contains(PerformanceControllerManagementI.class))
			offeredInterfaces.add(PerformanceControllerManagementI.class);

		PerformanceControllerManagementInboundPort pcmip =
				new PerformanceControllerManagementInboundPort(
						performanceControllerManagementInboundPortURI, 
						PerformanceControllerManagementI.class, 
						this);
		addPort(pcmip);
		pcmip.publishPort();

		if ( !offeredInterfaces.contains(PerformanceControllerServicesI.class))
			offeredInterfaces.add(PerformanceControllerServicesI.class);

		PerformanceControllerServicesInboundPort pcsip =
				new PerformanceControllerServicesInboundPort(
						performanceControllerServicesInboundPortURI, 
						PerformanceControllerServicesI.class, 
						this);
		addPort(pcsip);
		pcsip.publishPort();
		
		if ( !offeredInterfaces.contains(LogicalResourcesProviderCoreReleasingNotificationI.class))
			offeredInterfaces.add(LogicalResourcesProviderCoreReleasingNotificationI.class);

		LogicalResourcesProviderCoreReleasingNotificationInboundPort pccrnip =
				new LogicalResourcesProviderCoreReleasingNotificationInboundPort(
						performanceControllerCoreReleasingNotificationInboundPortURI, 
						LogicalResourcesProviderCoreReleasingNotificationI.class, 
						this);
		addPort(pccrnip);
		pccrnip.publishPort();
	}

	String generateLogicalResourcesProviderUri(String ownerURI, Object tag) {
		return ownerURI + " : " + tag.toString() + "_" + (performanceController.findByURI(Branch.LOGICAL_RESOURCES_PROVIDERS).children.size() + 1);
	}

	String generatePerformanceControllerUri(String ownerURI, Object tag) {
		return ownerURI + " : " + tag.toString() + "_" + (performanceController.findByURI(Branch.PERFORMANCE_CONTROLLER).children.size() + 1);
	}

	String generateRequestGeneratorUri(String ownerURI, Object tag) {
		return ownerURI + " : " + tag.toString() + "_" + (performanceController.findByURI(Branch.REQUEST_GENERATORS).children.size() + 1);
	}

	String generateDispatcherUri(String ownerURI, Object tag) {
		return ownerURI + " : " + tag.toString() + "_" + (performanceController.findByURI(Branch.DISPATCHERS).children.size() + 1);
	}

	@Override
	public void connectLogicalResourcesProvider(LogicalResourcesProviderPortsDataI lrppdi) throws Exception {
		String	lrpURI = lrppdi.getUri(),
				lrpsipURI = lrppdi.getLogicalResourcesProviderServicesInboundPort(),
				lrpsopURI = generateLogicalResourcesProviderUri(lrpURI, Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_OUTBOUND_PORT);
		ComponentDataNode lrpdn = new ComponentDataNode(lrpURI)
				.addPort(lrppdi.getLogicalResourcesProviderServicesInboundPort());

		performanceController.findByURI(Branch.LOGICAL_RESOURCES_PROVIDERS).addChild(lrpdn);

		if ( !requiredInterfaces.contains(LogicalResourcesProviderServicesI.class) )
			requiredInterfaces.add(LogicalResourcesProviderServicesI.class);

		LogicalResourcesProviderServicesOutboundPort lrpsop = 
				new LogicalResourcesProviderServicesOutboundPort(
						lrpsopURI, 
						LogicalResourcesProviderServicesI.class,
						this);
		addPort(lrpsop);
		lrpsop.publishPort();
		lrpsop.doConnection(lrppdi.getLogicalResourcesProviderServicesInboundPort(), LogicalResourcesProviderServicesConnector.class.getCanonicalName());

		performanceController.trustedConnect(lrpsopURI, lrpsipURI);
	}

	@Override
	public void disconnectLogicalResourcesProvider(LogicalResourcesProviderPortsDataI lrppdi) throws Exception {		
		ComponentDataNode lrpdn = performanceController.findByURI(lrppdi.getUri());
		String	lrpsipURI = lrpdn.getPortLike(Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_INBOUND_PORT),
				lrpsopURI = lrpdn.getPortConnectedTo(lrpsipURI);
		PortI	lrpsop = findPortFromURI(lrpsopURI);

		lrpsop.doDisconnection();
		lrpsop.destroyPort();

		performanceController.disconnect(lrpsopURI);
		performanceController.findByURI(Branch.LOGICAL_RESOURCES_PROVIDERS).removeChild(lrpdn);
	}

	@Override
	public void connectPerformanceController(PerformanceControllerPortsDataI cpdi) throws Exception {
		// TODO Auto-generated method stub

	}

	@Override
	public void disconnectPerformanceController(PerformanceControllerPortsDataI cpdi) throws Exception {
		// TODO Auto-generated method stub

	}

	@Override
	public AllocatedRequestGenerator createAllocatedRequestGenerator() throws Exception {
		final String rgURI = generateRequestGeneratorUri(performanceController.uri, Tag.REQUEST_GENERATOR);
		double meanInterArrivalTime = 500;
		long meanNumberOfInstructions = 10000000000L;
		final String rgmipURI = generateRequestGeneratorUri(rgURI, Tag.REQUEST_GENERATOR_MANAGEMENT_INBOUND_PORT);
		final String rgrsopURI = generateRequestGeneratorUri(rgURI, Tag.REQUEST_SUBMISSION_OUTBOUND_PORT);
		final String rgrnipURI = generateRequestGeneratorUri(rgURI, Tag.REQUEST_NOTIFICATION_INBOUND_PORT);

		AllocatedRequestGenerator arg = new AllocatedRequestGenerator(
				rgURI, 
				meanInterArrivalTime, 
				meanNumberOfInstructions, 
				rgmipURI, 
				rgrsopURI, 
				rgrnipURI);

		return arg;
	}

	@Override
	public RequestGenerator createRequestGenerator(AllocatedRequestGenerator arg) throws Exception {
		RequestGenerator rgn = 
				new RequestGenerator(
						arg.rgURI, 
						arg.meanInterArrivalTime, 
						arg.meanNumberOfInstructions,
						arg.rgmipURI, 
						arg.rgrsopURI,
						arg.rgrnipURI);
		AbstractCVM.theCVM.addDeployedComponent(rgn);
		rgn.start();

		return rgn;
	}

	@Override
	public AllocatedDispatcher createAllocatedDispatcher() throws Exception {
		final String dspURI = generateDispatcherUri(performanceController.uri, Tag.DISPATCHER);
		final String dspmipURI = generateDispatcherUri(dspURI, Tag.DISPATCHER_MANAGEMENT_INBOUND_PORT);
		final String avmrnop = generateDispatcherUri(dspURI, Tag.APPLICATION_VM_RELEASING_NOTIFICATION_OUTBOUND_PORT);
		final String dspdsdip = generateDispatcherUri(dspURI, Tag.DISPATCHER_DYNAMIC_STATE_DATA_INBOUND_PORT);

		AllocatedDispatcher adsp = new AllocatedDispatcher(
				dspURI, 
				dspmipURI, 
				avmrnop, 
				dspdsdip);

		return adsp;
	}

	@Override
	public Dispatcher createDispatcher(AllocatedDispatcher adsp) throws Exception {		
		Dispatcher dsp = new Dispatcher(
				adsp.dspURI, 
				adsp.dspmipURI, 
				adsp.avmrnopURI,
				adsp.dspdsdipURI);
		AbstractCVM.theCVM.addDeployedComponent(dsp);
		dsp.start();

		return dsp;
	}

	@Override
	public void acceptApplication() throws Exception {

		if ( !MULTIAPPLICATION && hasApplication )
			throw new Exception("An application was already started on this controller");

		AllocatedRequestGenerator argn = createAllocatedRequestGenerator();
		AllocatedDispatcher adsp = createAllocatedDispatcher();
		RequestGenerator rgn = createRequestGenerator(argn);
		dsp = createDispatcher(adsp);

		ComponentDataNode dspdn = new ComponentDataNode(adsp.dspURI)
				.addPort(adsp.avmrnopURI)
				.addPort(adsp.dspdsdipURI)
				.addPort(adsp.dspmipURI);

		ComponentDataNode rgdn = new ComponentDataNode(argn.rgURI)
				.addPort(argn.rgmipURI)
				.addPort(argn.rgrnipURI)
				.addPort(argn.rgrsopURI);

		String lrpsopURI = performanceController.getPortLike(Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_OUTBOUND_PORT);	
		LogicalResourcesProviderServicesOutboundPort lrpsop = (LogicalResourcesProviderServicesOutboundPort) findPortFromURI(lrpsopURI);

		AllocatedApplicationVM[] aavms = lrpsop.allocateApplicationVMs(1);

		if (aavms.length < 1)
			throw new NoApplicationVMException("Cannot allocate new avm for this application");

		ComponentDataNode avmdn = new ComponentDataNode(aavms[0].avmURI)
				.addPort(aavms[0].avmcripURI)
				.addPort(aavms[0].avmcrnopURI)
				.addPort(aavms[0].avmmipURI)
				.addPort(aavms[0].avmrnopURI)
				.addPort(aavms[0].avmrsipURI);

//		adds.adsp = adsp;
//		adds.setRequestGenerator(argn);
//		adds.updateAllocatedApplicationVMCoreCount(aavms[0], 1);
		
		//TODO
		
		Integer[] coreCounts = new Integer[1];
		coreCounts[0] = 1;
		pcds.addAllocatedDispatcher(adsp, argn, aavms, coreCounts);

		/**
		 * Ici la demande de resources � fonctionn� et il faut maintenant connecter le dispatcher, � l'avm et au requestGenerator 
		 */

		if ( !requiredInterfaces.contains(RequestGeneratorManagementI.class) )
			requiredInterfaces.add(RequestGeneratorManagementI.class);

		String rgmopURI = generateRequestGeneratorUri(performanceController.uri, Tag.REQUEST_GENERATOR_MANAGEMENT_OUTBOUND_PORT);
		RequestGeneratorManagementOutboundPort rgmop = new RequestGeneratorManagementOutboundPort(rgmopURI, this);
		addPort(rgmop);
		rgmop.publishPort();
		rgmop.doConnection(argn.rgmipURI, RequestGeneratorManagementConnector.class.getCanonicalName());

		
		if ( !requiredInterfaces.contains(DispatcherManagementI.class) )
			requiredInterfaces.add(DispatcherManagementI.class);

		String dspmopURI = generateDispatcherUri(performanceController.uri, Tag.DISPATCHER_MANAGEMENT_OUTBOUND_PORT);
		DispatcherManagementOutboundPort dspmop = 
				new DispatcherManagementOutboundPort(dspmopURI, DispatcherManagementI.class, this);
		addPort(dspmop);
		dspmop.publishPort();
		dspmop.doConnection(adsp.dspmipURI, DispatcherManagementConnector.class.getCanonicalName());

		
		if ( !requiredInterfaces.contains(ControlledPullI.class) )
			requiredInterfaces.add(ControlledPullI.class);

		String dspdsdopURI = generateDispatcherUri(performanceController.uri, Tag.DISPATCHER_DYNAMIC_STATE_DATA_OUTBOUND_PORT);
		DispatcherDynamicStateDataOutboundPort dspdsdop =
				new DispatcherDynamicStateDataOutboundPort(dspdsdopURI, this);
		addPort(dspdsdop);
		dspdsdop.publishPort();
		dspdsdop.doConnection(adsp.dspdsdipURI, ControlledDataConnector.class.getCanonicalName());

		
		if (!offeredInterfaces.contains(ApplicationVMReleasingNotificationI.class))
			addOfferedInterface(ApplicationVMReleasingNotificationI.class);

		final String avmrnipURI = generateDispatcherUri(performanceController.uri, Tag.APPLICATION_VM_RELEASING_NOTIFICATION_INBOUND_PORT);

		ApplicationVMReleasingNotificationInboundPort avmrnip = 
				new ApplicationVMReleasingNotificationInboundPort(avmrnipURI, ApplicationVMReleasingNotificationI.class, this);
		addPort(avmrnip);
		avmrnip.publishPort();



		ApplicationVMReleasingNotificationOutboundPort avmrnop = (ApplicationVMReleasingNotificationOutboundPort) dsp
				.findPortFromURI(dsp.getApplicationVMReleasingNotificationOutboundPortURI());
		avmrnop.doConnection(avmrnipURI, ApplicationVMReleasingNotificationConnector.class.getCanonicalName());



		/** Connection du dispatcher au autres composant pour la g�n�ration dynamique des inbounds port � connecter **/

		String dsprnipURI = dspmop.connectToApplicationVM(aavms[0].avmrsipURI);
		String dsprsipURI = dspmop.connectToRequestGenerator(argn.rgrnipURI);
		String dsprnopURI = dsp.getRequestNotificationOutboundPortURI();
		String dsprsopURI = dsp.getRequestSubmissionOutboundPortURIs().get(dsp.getRequestSubmissionOutboundPortURIs().size() - 1);

		adsp.dsprnipURI = dsprnipURI;
		adsp.dsprsipURI = dsprsipURI;

		dspdn	.addPort(dsprnipURI)
		.addPort(dsprsipURI);

		/** Connexion du g�n�rateur de requ�tes au dispatcher **/

		RequestSubmissionOutboundPort rgrsop = (RequestSubmissionOutboundPort) rgn.findPortFromURI(argn.rgrsopURI);
		rgrsop.doConnection(dsprsipURI, RequestSubmissionConnector.class.getCanonicalName());


		/** Connexion de l'avm au dispatcher (action distante) **/

		lrpsop.connectApplicationVM(aavms[0], adsp);

		performanceController.findByURI(Branch.APPLICATION_VMS).addChild(avmdn);
		performanceController.findByURI(Branch.DISPATCHERS).addChild(dspdn);
		performanceController.findByURI(Branch.REQUEST_GENERATORS).addChild(rgdn);

		performanceController.trustedConnect(rgmopURI, argn.rgmipURI);
		performanceController.trustedConnect(dspmopURI, adsp.dspmipURI);
		performanceController.trustedConnect(dspdsdopURI, adsp.dspdsdipURI);
		performanceController.addPort(avmrnipURI);
		dspdn.trustedConnect(dsp.getApplicationVMReleasingNotificationOutboundPortURI(), avmrnipURI);
		dspdn.trustedConnect(dsprnopURI, argn.rgrnipURI);
		dspdn.trustedConnect(dsprsopURI, aavms[0].avmrsipURI);
		rgdn.trustedConnect(argn.rgrsopURI, dsprsipURI);
		avmdn.trustedConnect(aavms[0].avmrnopURI, dsprnipURI);

		/** TODO PARAMETRES **/

		//Dispatcher.DYNAMIC_STATE_DATA_DISPLAY = true;
		rgmop.startGeneration();
		dspdsdop.startUnlimitedPushing(DISPATCHER_PUSHING_INTERVAL);

		hasApplication = true;
	}

	@Override
	public void acceptDispatcherDynamicStateData(DispatcherDynamicStateI data) throws Exception {
		dspddsimap.put(data.getDispatcherURI(), data);
		logs.add(data);
		controlLaw();
	}

	@Override
	public void controlLaw() throws Exception {

		double 	avgduration = -1;
		double 	avgusage = -1;
		int 	totalAllocatedCores = 0;
		int		totalPendings = 0;

		ticking++;

		logMessage(tickString());
		
		try {
			
			if ( logs.size() > 20 ) {

				DispatcherDynamicStateI mainLog = logs.remove(0);
				boolean isFrozen = true;

				for ( String rsopURI : mainLog.getExponentialAverages().keySet() ) {
					
					for ( DispatcherDynamicStateI log : logs ) {
												
						if (	(mainLog.getExponentialAverages().get(rsopURI) == null) |
								(mainLog.getPendingRequests().get(rsopURI) == null) |
								(mainLog.getPerformedRequests().get(rsopURI) == null)	) {
							isFrozen = false;
							break;
						}

						if (	(log.getExponentialAverages().get(rsopURI) == null) |
								(log.getPendingRequests().get(rsopURI) == null) |
								(log.getPerformedRequests().get(rsopURI) == null)	) {
							isFrozen = false;
							break;
						}

						isFrozen &= 
								( mainLog.getExponentialAverages().get(rsopURI).getValue().getMilliseconds() == log.getExponentialAverages().get(rsopURI).getValue().getMilliseconds() ) &
								( mainLog.getPendingRequests().get(rsopURI).size() == log.getPendingRequests().get(rsopURI).size() ) &
								( mainLog.getPerformedRequests().get(rsopURI) == log.getPerformedRequests().get(rsopURI) );
						
					}
					
					if ( isFrozen ) {

						ComponentDataNode avmdn = performanceController.findByConnectedPort(rsopURI);

						if ( avmdn == null )
							throw new Exception("L'application VM � surment d� �tre lib�r�e mais est toujours en terminaison");

						AllocatedApplicationVM aavm = null;		

						for ( AllocatedApplicationVM elt : pcds.getAllocatedApplicationVMs() ) {

							if ( avmdn.uri.equals(elt.avmURI) ) {
								aavm = elt;
								break;
							}
						}

						if ( aavm == null ) {
							System.out.println(performanceController.graphToString());
							throw new Exception("AAVM null !");

						}

						System.err.println(aavm.avmURI + " is frozen ! Simulation ended ");
						System.exit(-1);
					}
					
				}

			}

			if ( dsp == null )
				return;

			String dspURI = dsp.getURI();
			DispatcherDynamicStateI dspdsi = dspddsimap.get(dspURI);

			if ( dspdsi == null )
				return;

			StringBuilder sb = new StringBuilder()
					.append("\t----------<AVMS>----------\n\n");

			for ( String rsopURI : dspdsi.getExponentialAverages().keySet() ) {
				ComponentDataNode avmdn = performanceController.findByConnectedPort(rsopURI);

				if ( avmdn == null )
					throw new Exception("L'application VM � surment d� �tre lib�r�e mais est toujours en terminaison");

				AllocatedApplicationVM aavm = null;		

				for ( AllocatedApplicationVM elt : pcds.getAllocatedApplicationVMs() ) {

					if ( avmdn.uri.equals(elt.avmURI) ) {
						aavm = elt;
						break;
					}
				}

				if ( aavm == null ) {
					System.out.println(performanceController.graphToString());
					throw new Exception("AAVM null !");

				}

				ExponentialAverage expavg = dspdsi.getExponentialAverages().get(rsopURI);
				Duration duration = expavg.getValue();
				Integer pendingsCount = dspdsi.getPendingRequests().get(rsopURI).size();
				Integer allocatedCoreCount = pcds.getAllocatedApplicationVMCoreCount(aavm);
				Double usagePercent = 100 * (pendingsCount.doubleValue()/allocatedCoreCount.doubleValue());
//				Integer avmCount = dspdsi.avmCount();

				/**
				 * Si la dur�e est � z�ro, c'est que la premi�re t�che de l'AVM n'est pas termin�e et 
				 * sa moyenne n'est pas encore d�finie (au moins 1 t�che de trait�e pour avoir une moyenne).
				 */

				if ( duration.getMilliseconds() == 0 )
					continue;

				/**
				 * Calcul des moyennes des AVM du r�partiteur de requ�tes. A initialiser � la premi�re valeur d'AVM � traiter.
				 */

				if ( avgduration == -1 )
					avgduration = duration.getMilliseconds();
				else
					avgduration = 0.5 * (avgduration + duration.getMilliseconds());

				totalAllocatedCores += allocatedCoreCount;
				totalPendings += pendingsCount;

				/** On affiche les r�sultats avant la loi de contr�le **/

				sb.append("\t\tAVM URI \t:\t").append(aavm.avmURI);
//				if ( !adds.getAllocatedApplicationVMs().contains(aavm) )
//					sb.append("\t<terminating>");

				sb
				.append("\n")
				.append("\t\tDURATION \t:\t").append(duration.getMilliseconds()).append("ms\n")
				.append("\t\tPENDINGS \t:\t").append(pendingsCount).append("\n")
				.append("\t\tALLOCATED CORES\t:\t").append(allocatedCoreCount).append("\n")
				.append("\t\tUSAGE\t\t:\t").append(pendingsCount).append("/").append(allocatedCoreCount).append(" (").append(usagePercent).append(")\n\n");


				/** Loi de contr�le pour chaque AVM TODO **/

				/**
				 * Correction des avaries dues � la non synchronisation du processus de lib�ration d'un coeur d'AVM entre
				 * contr�leur de performances et son fournisseur de resources logiques.
				 */

//				if (ticking % 2 == 0)
//				performIncreaseApplicationVMCores(aavm, 2);
//				if (ticking % 3 == 0)
//				performDecreaseApplicationVMCores(aavm, 1);
//				if (ticking % 4 == 0)
//				performIncreaseApplicationVMCores(aavm, 1);
//				if (ticking % 5 == 0)
//				performDecreaseApplicationVMCores(aavm, 2);
				
//				if ( allocatedCoreCount == 0 ) {
//					logMessage("AVM without core detected, it will be corrected");
//					performIncreaseApplicationVMCores(aavm, 1);
//				}
//
				sb.append("\t\tIS NOT CHANGING :\t\t").append(!pcds.isChanging(aavm)).append("\n");
//				if ( !pcds.isChanging(aavm) ) {
//					if ( (duration.getMilliseconds() > 5000) || (usagePercent > 500) ) {
//						performIncreaseApplicationVMCores(aavm, (int) (usagePercent * 0.01 + (duration.getMilliseconds() - 5000) * 0.001));
//					} else if ( duration.getMilliseconds() > 3000 ) {
//						performIncreaseApplicationVMFrequency(aavm);
//					} else if ( duration.getMilliseconds() > 2000 ) {
//						performDecreaseApplicationVMFrequency(aavm);
//					} else if ( (duration.getMilliseconds() < 2000) || (usagePercent < 50) ) {
//						performDecreaseApplicationVMCores(aavm, (int) ( allocatedCoreCount * 0.5 + (duration.getMilliseconds()) * 0.001));
//					}
//				}

				if ( allocatedCoreCount == 1 && !pcds.isChanging(aavm) )
					performIncreaseApplicationVMCores(aavm, 7);
				else if ( !pcds.isChanging(aavm) )
					performDecreaseApplicationVMCores(aavm, 1);
			}

			Integer avmCount = dspdsi.avmCount();
			
			avgusage = ((double)totalPendings/totalAllocatedCores);

//			if ( ((avmCount * MAXIMUM_CORES_BY_AVM) ==  totalAllocatedCores) )
//				performAllocateApplicationVM(1);
			
//			if ( ((avgduration > 8000) || (avgusage > 300)) && ((avmCount * MAXIMUM_CORES_BY_AVM) ==  totalAllocatedCores))
//				performAllocateApplicationVM(1);
//
//			if ( ((avgduration < 2000) || (avgusage < 50)) && (avmCount - releasing > 1) )
//				performReleaseApplicationVM(1);

			sb
			.append("\t\t--------<AVERAGES>--------\n\n")
			.append("\t\tAVM COUNT \t:\t").append(avmCount).append("\n")
			.append("\t\tALLOCATED CORES\t:\t").append(totalAllocatedCores).append("\n")
			.append("\t\tAVG DURATION \t:\t").append((avgduration != -1) ? avgduration : "none ").append("ms\n")
			.append("\t\tAVG USAGE \t:\t").append(100 * avgusage).append("%\n\n")
			.append("\t\tRELEASING \t:\t").append(releasing).append("\n");

			logMessage(sb.toString());

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}

	}

	@Override
	public synchronized void performIncreaseApplicationVMFrequency(AllocatedApplicationVM aavm) throws Exception {
		String	lrpsopURI = performanceController.getPortLike(Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_OUTBOUND_PORT);		
		LogicalResourcesProviderServicesOutboundPort lrpsop = (LogicalResourcesProviderServicesOutboundPort) findPortFromURI(lrpsopURI);

		logMessage("performIncreaseApplicationVMFrequency for [" + aavm.avmURI + "]");

		lrpsop.increaseApplicationVMFrequency(aavm);
	}

	@Override
	public synchronized void performDecreaseApplicationVMFrequency(AllocatedApplicationVM aavm) throws Exception {
		String	lrpsopURI = performanceController.getPortLike(Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_OUTBOUND_PORT);		
		LogicalResourcesProviderServicesOutboundPort lrpsop = (LogicalResourcesProviderServicesOutboundPort) findPortFromURI(lrpsopURI);

		logMessage("performDecreaseApplicationVMFrequency  for [" + aavm.avmURI + "]");

		lrpsop.decreaseApplicationVMFrequency(aavm);
	}

	@Override
	public synchronized void performIncreaseApplicationVMCores(AllocatedApplicationVM aavm, int wantedCores) throws Exception {
		String	lrpsopURI = performanceController.getPortLike(Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_OUTBOUND_PORT);		
		LogicalResourcesProviderServicesOutboundPort lrpsop = (LogicalResourcesProviderServicesOutboundPort) findPortFromURI(lrpsopURI);

		logMessage(tickString() + "performIncreaseApplicationVMCores for [" + aavm.avmURI + "]");

		int coreCount = pcds.getAllocatedApplicationVMCoreCount(aavm);

		if (coreCount + wantedCores > MAXIMUM_CORES_BY_AVM)
			wantedCores = MAXIMUM_CORES_BY_AVM - coreCount;

		if ( wantedCores > 0 ) {
			
			/**
			 * Une tentative d'augmentation du nombre de coeurs est pr�vue sur une m�me
			 * AVM. Cependant si le fournisseur de resources physiques attach� au fournisseur 
			 * de resources logiques ne poss�de plus de coeurs sur un m�me ordinateur, il y a
			 * lev�e de NoCoreException. Il faut alors, soit tenter directement une demande de nouvelle
			 * AVM, ou bien laisser la loi de contr�le s'en charger mais dans tous les cas l'exception
			 * doit �tre trait�e ici. 
			 */
		
			int increment = lrpsop.increaseApplicationVMCores(aavm, wantedCores);
			
			if (increment == 0) {
				performAllocateApplicationVM(1); 
			} else {
			
				/**
				 * Ajoute un nouvel �tat changeant qui est une augmentation du nombre wantedCores,
				 * les allocations �tant sans d�lai de r�action, on peut mettre � jour l'�tat dynamique
				 * du contr�leur sans risque de cr�er d'incoh�rence � priori
				 */
				
				pcds.pushAllocatedApplicationVMChangingState(aavm, wantedCores);
				
				for (int i = 0; i < wantedCores; i++)
					pcds.step(aavm);
			}
		}

		logMessage(tickString() + "performIncreaseApplicationVMCores for [" + aavm.avmURI + "] END");
	}

	@Override
	public synchronized void performDecreaseApplicationVMCores(AllocatedApplicationVM aavm, int wantedCores) throws Exception {
		String	lrpsopURI = performanceController.getPortLike(Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_OUTBOUND_PORT);		
		LogicalResourcesProviderServicesOutboundPort lrpsop = (LogicalResourcesProviderServicesOutboundPort) findPortFromURI(lrpsopURI);

		logMessage(tickString() + "performDecreaseApplicationVMCores for [" + aavm.avmURI + "]");

		int coreCount = pcds.getAllocatedApplicationVMCoreCount(aavm);

		System.out.println(coreCount);

		if (coreCount - wantedCores < MINIMUM_CORES_BY_AVM)
			wantedCores = coreCount - MINIMUM_CORES_BY_AVM;

		System.out.println("WANTED CORES FOR DECREASE : " + wantedCores);

		if ( wantedCores > 0 ) {
			/**
			 * Contrairement � l'augmentation de nombre de coeur, la diminution engendre des probl�mes
			 * de d�lai d'attente et l'�volution de l'�tat doit �tre mis � jour que par notification 
			 * explicite du fournisseur de resources logiques
			 */
			
			pcds.pushAllocatedApplicationVMChangingState(aavm, -wantedCores);
			
			coreCount = lrpsop.decreaseApplicationVMCores(aavm, wantedCores); // Taille pr�vue par la lib�ration
			if ( coreCount == 0 ) {
				System.out.println("Asynchronism incoherence : coreCount == 0 ");
				System.out.println("Try auto-fix it by allocating a new core");
				performIncreaseApplicationVMCores(aavm, 1);
			}
			
//			adds.updateAllocatedApplicationVMCoreCount(aavm, coreCount); // Peut conduire � une allocation trop grande (Ajouter un port de notification pour mettre � jour cette valeur)
		}

		logMessage(tickString() + "performDecreaseApplicationVMCores for [" + aavm.avmURI + "] END");
	}

	@Override
	public synchronized void performAllocateApplicationVM(int wantedAavms) throws Exception {
		String	lrpsopURI = performanceController.getPortLike(Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_OUTBOUND_PORT);		
		LogicalResourcesProviderServicesOutboundPort lrpsop = (LogicalResourcesProviderServicesOutboundPort) findPortFromURI(lrpsopURI);

		logMessage(tickString() + "performAllocateApplicationVM");
		
		String dspmopURI = performanceController.getPortLike(Tag.DISPATCHER_MANAGEMENT_OUTBOUND_PORT);
		ComponentDataNode dspdn = performanceController.findByConnectedPort(dspmopURI);
		AllocatedDispatcher adsp = new AllocatedDispatcher(
				pcds.getSingleAllocatedDispatcher().dspURI, 
				pcds.getSingleAllocatedDispatcher().dspmipURI, 
				pcds.getSingleAllocatedDispatcher().avmrnopURI, 
				pcds.getSingleAllocatedDispatcher().dspdsdipURI);
		
		
		AllocatedApplicationVM[] aavms = lrpsop.allocateApplicationVMs(wantedAavms);

		for ( int i = 0; i < aavms.length; i++ ) {

			ComponentDataNode avmdn = new ComponentDataNode(aavms[i].avmURI)
					.addPort(aavms[i].avmcripURI)
					.addPort(aavms[i].avmcrnopURI)
					.addPort(aavms[i].avmmipURI)
					.addPort(aavms[i].avmrnopURI)
					.addPort(aavms[i].avmrsipURI);

//			adds.updateAllocatedApplicationVMCoreCount(aavms[i], 1);
			pcds.addAllocatedApplicationVM(pcds.getSingleAllocatedDispatcher(), aavms[i], 1);

			DispatcherManagementOutboundPort dspmop = (DispatcherManagementOutboundPort) findPortFromURI(dspmopURI);

			List<String> dsprsopsURI_before = new ArrayList<>(dsp.getRequestSubmissionOutboundPortURIs()); 

			String dsprnipURI = dspmop.connectToApplicationVM(aavms[i].avmrsipURI);			
			adsp.dsprnipURI = dsprnipURI;

			List<String> dsprsopsURI_after = new ArrayList<>(dsp.getRequestSubmissionOutboundPortURIs());

			dsprsopsURI_after.removeAll(dsprsopsURI_before);

			if (dsprsopsURI_after.size() != 1)
				throw new Exception("Connection increase dsprsop more than expected");

			String dsprsopURI = dsprsopsURI_after.get(0);
			lrpsop.connectApplicationVM(aavms[i], adsp);

			performanceController.findByURI(Branch.APPLICATION_VMS).addChild(avmdn);

			performanceController.findByURI(adsp.dspURI).addPort(dsprnipURI);

			try {

				dspdn.trustedConnect(dsprsopURI, aavms[i].avmrsipURI);
				avmdn.trustedConnect(aavms[i].avmrnopURI, dsprnipURI);

				//			System.out.println("BIG BUG");
				//			for ( ComponentDataNode dn : performanceController.findByURI(Branch.APPLICATION_VMS).children)
				//				System.out.println(dn.uri);
				//			System.out.println();
				//			System.out.println(dspdn);
				//			System.out.println(avmdn);
				//			System.out.println(dsprsopURI + " +++++ " + aavms[i].avmrsipURI);
				//			System.out.println(aavms[i].avmrnopURI + " +++++ " + dsprnipURI);

			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-32);
			}
		}
		
		logMessage(tickString() + "performAllocateApplicationVM END");

	}

	@Override
	public synchronized void performReleaseApplicationVM(Integer wantedAavms) throws Exception {

		logMessage("performReleaseApplicationVM");

		if (wantedAavms > pcds.getAllocatedApplicationVMs().size() )
			wantedAavms = pcds.getAllocatedApplicationVMs().size();

		for ( int i = 0; i < wantedAavms; i++ ) {

			releasing++;

			String dspmopURI = performanceController.getPortLike(Tag.DISPATCHER_MANAGEMENT_OUTBOUND_PORT);
			DispatcherManagementOutboundPort dspmop = (DispatcherManagementOutboundPort) findPortFromURI(dspmopURI);

			dspmop.disconnectFromApplicationVM();
		}
	}

	@Override
	public void acceptApplicationVMReleasing(String dispatcherURI, String rsopURI, String rnipURI) throws Exception {

		try {
			System.out.println(rnipURI);

			String	lrpsopURI = performanceController.getPortLike(Tag.LOGICAL_RESOURCES_PROVIDER_SERVICES_OUTBOUND_PORT);		
			LogicalResourcesProviderServicesOutboundPort lrpsop = (LogicalResourcesProviderServicesOutboundPort) findPortFromURI(lrpsopURI);
			ComponentDataNode avmdn = performanceController.findByConnectedPort(rsopURI);
			ComponentDataNode dspdn = performanceController.findByURI(pcds.getSingleAllocatedDispatcher().dspURI);

			for ( AllocatedApplicationVM aavm : pcds.getAllocatedApplicationVMs() ) {
				if ( aavm.avmURI.equals(avmdn.uri) ) {

					//				System.out.println("DEBUG : " + avmdn);

					AllocatedDispatcher adsp = new AllocatedDispatcher(
							pcds.getSingleAllocatedDispatcher().dspURI, 
							pcds.getSingleAllocatedDispatcher().dspmipURI, 
							pcds.getSingleAllocatedDispatcher().avmrnopURI, 
							pcds.getSingleAllocatedDispatcher().dspdsdipURI);
					adsp.dsprnipURI = rnipURI;

					AllocatedApplicationVM[] toRelease = {aavm};
					lrpsop.releaseApplicationVMs(toRelease);

					lrpsop.disconnectApplicationVM(aavm, adsp);

					logMessage("[" +aavm.avmURI + "] has terminated");

					pcds.removeAllocatedApplicationVM(aavm);

					String rnopURI = avmdn.getPortLike(Tag.REQUEST_NOTIFICATION_OUTBOUND_PORT);

					dspdn.disconnect(rsopURI);
					avmdn.disconnect(rnopURI);

					dspdn.removePort(rnipURI);
					dspdn.removePort(rsopURI);

					assert avmdn.connections.size() == 0;

					performanceController.findByURI(Branch.APPLICATION_VMS).removeChild(avmdn);

					releasing--;

					break;
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-42);
		}
	}

	private String tickString() {
		return " <" + ticking + "> ";
	}

	@Override
	public void acceptCoreReleasingNotification(AllocatedApplicationVM aavm) throws Exception {
		System.out.println("STEPPING");
		pcds.step(aavm);
	}

}