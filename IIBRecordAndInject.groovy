// the IIB Integration API
import com.ibm.broker.config.proxy.*;
import com.ibm.broker.config.common.*;
import groovy.io.FileType
import groovy.io.FileVisitResult

//Compare libs
import org.xmlunit.builder.DiffBuilder
import org.xmlunit.builder.Input
import org.xmlunit.diff.ByNameAndTextRecSelector
import org.xmlunit.diff.DefaultNodeMatcher
import org.xmlunit.diff.ElementSelectors

String LS = System.properties['line.separator']
String FS = System.properties['file.separator']

def cli = new CliBuilder(
   usage: 'iib-test.groovy [options]',
   header: '\nAvailable options (use -h for help):\n',
   footer: '\nInformation provided via above options is used to generate printed string.\n')
cli.with
{
   r(longOpt: 'record', 'Mode set to record', args: 0, argName:'RECORD', required: false)
   i(longOpt: 'inject', 'Mode set to inject', args: 0, argName:'INJECT', required: false)
   c(longOpt: 'contract', 'Contract', args: 1, argName:'CONTRACT', required: true)
   a(longOpt: 'application', 'Application name', args: 1, argName: 'APPLICATION', required: false)
   s(longOpt: 'ip', 'ip adress of the broker', args: 1, argName:'BROKER', required: false)
   p(longOpt: 'port', 'port of the broker', args: 1, argName:'PORT', required: false)
   e(longOpt: 'eg', 'Execution group', args: 1, argName:'EG', required: false)
   w(longOpt: 'workDir', 'Working dir, if not specified the current working directory will be used', args:1, argName:'WORK_DIR', required: false)
   h(longOpt: 'help', 'Help', args: 0, required: false)
}


def opt = cli.parse(args)
if (!opt) return
if (opt.h) {
	println usageStructure
	cli.usage()
}

	def integrationServerName = opt.e ?: 'default'
	def applicationName = opt.a
	def ipAdress = opt.s ?: 'localhost'
	def port = opt.p ?: '4414'
	def contractId = opt.c
	boolean record = opt.r
	boolean inject = opt.i
	def mode = record ? 'record' : 'inject' 
	
	def workspaceDirName = opt.w ?:  System.properties['user.dir']  
	
	
	createDefaultDirStructure(contractId, mode,workspaceDirName)
	println "INFO: ${mode}ing for $applicationName on ${ipAdress}($port)"
	try {
        // Integration API initialization
		BrokerConnectionParameters bcp = new IntegrationNodeConnectionParameters(ipAdress, port.toInteger())
		BrokerProxy proxy = BrokerProxy.getInstance(bcp)
		println "INFO: Connecting to ${proxy.name}"
		
		ExecutionGroupProxy serverProxy = proxy.getExecutionGroupByName(integrationServerName);
		if (serverProxy.isRunning()) {
			println "INFO: $integrationServerName is running.."
			serverProxy.setTestRecordMode(AttributeConstants.MODE_ENABLED);
			serverProxy.setInjectionMode(AttributeConstants.MODE_ENABLED);
			
			//sleep(1000) 
			
			if (record) {
				def recordedMessages = getRecordedTestDataFromHost(mode, contractId, applicationName, serverProxy) 
				if (recordedMessages != mode) {
					println "INFO: Fetching recorded messages for $applicationName"
					
				} else {
					println "INFO: recording is on, send data to save"
				}
			}
		
			
			if (inject) {
				println "INFO: Fetching messages to inject in $applicationName"
				 boolean result 
				 new File(workspaceDirName + FS + 'record' + FS + contractId).traverse(type:FileType.FILES, nameFilter:~/.*FCMComposite_1_1&-.*\.xml$/, preDir:{ return FileVisitResult.SKIP_SUBTREE }){ file ->  
					
					println "INFO: Found test file: $file.name" 
					def splitName = file.name.split('-')  
				
					
					if (splitName[1].contains('FCMComposite_1_1&')) {
						println "INFO: Injecting message"
						
						Properties injectionProps = new Properties()
						injectionProps.setProperty(AttributeConstants.DATA_INJECTION_APPLICATION_LABEL, splitName[0])
						injectionProps.setProperty(AttributeConstants.DATA_INJECTION_MESSAGEFLOW_LABEL, splitName[2])
						injectionProps.setProperty(AttributeConstants.DATA_INJECTION_NODE_UUID, splitName[1] - '&')
						injectionProps.setProperty(AttributeConstants.DATA_INJECTION_MESSAGE_SECTION, file.text)
						injectionProps.setProperty(AttributeConstants.DATA_INJECTION_WAIT_TIME, "60000")
						
						result = serverProxy.injectTestData(injectionProps, true)
					}
					
					
				}
				if (result) {
					println "INFO: Message(s) injected"
					println "INFO: Saving output"
					getRecordedTestDataFromHost(mode, contractId, applicationName, serverProxy, workspaceDirName) 
					compareTheInjectToTheRecored(contractId, workspaceDirName)
				} else {
					println "ERROR: Injection failed.."
					System.exit 1
				}
			
				
			}
		}

	  }  catch (ConfigManagerProxyPropertyNotInitializedException e) { 
			println "ERROR: $e"
	  } catch (ConfigManagerProxyLoggedException e) { 
			println "ERROR: $e"			
	 }
	 
	
	def createDefaultDirStructure(String integrationId, String mode, String workspaceDirName) {
		def outputDir = new File(workspaceDirName + '/' +  mode + '/' + integrationId)
				
				if (!outputDir.exists()) {	
					outputDir.mkdirs()
				}
	}
	def getRecordedTestDataFromHost (String mode, String integrationId, String applicationName, ExecutionGroupProxy serverProxy, String workspaceDirName) {
		Properties filterPropsInjected = new Properties()
		filterPropsInjected.setProperty(Checkpoint.PROPERTY_APPLICATION_NAME, applicationName)
		List recordedMessages = serverProxy.getRecordedTestData(filterPropsInjected)
			
			if (recordedMessages) {
				recordedMessages.each{
							
					def sourceNodeName =  it.getPropertyValue('sourceNodeName')
					def sequenceNumber =  it.getPropertyValue('sequenceNumber')
					def messageFlowName = it.getPropertyValue('messageFlowName')
					def applicationNameEntry = it.getPropertyValue('applicationName')
					
					def recordedData = new File(workspaceDirName + '/' +  mode + '/' + integrationId + '/' + applicationNameEntry + '-' + sourceNodeName + '&-' + messageFlowName + '-' + sequenceNumber + '.xml')
					recordedData << it.getTestData().getMessage()
				}
			
				serverProxy.clearRecordedTestData()
				serverProxy.setInjectionMode(AttributeConstants.MODE_DISABLED)
				serverProxy.setTestRecordMode(AttributeConstants.MODE_DISABLED)
				
				return applicationName
			} else {
				return mode 
			}
	
	}
	def compareTheInjectToTheRecored(String integrationId, String workspaceDirName) {
		def injectDir = new File(workspaceDirName + '/' + 'inject/' + integrationId)
		
		injectDir.traverse(type:FileType.FILES, nameFilter:~/(^.*.xml)/, preDir:{ return FileVisitResult.SKIP_SUBTREE }){ file ->  
			def splitName = file.name.split('-')  
			
			if (!splitName[1].contains('#FCMComposite_1_1&')) {
				def myDiff = DiffBuilder.compare(Input.fromString(new File(workspaceDirName + '/' + 'record/'+ integrationId + '/' + file.name).text))
					.withTest(Input.fromString(file.text))
					.checkForSimilar()
					.withNodeMatcher(new DefaultNodeMatcher(new ByNameAndTextRecSelector(),ElementSelectors.byName))
					.build()
							
					println "INFO: Injection message diff to recorded: " + myDiff.toString()
						
				}
					
		}
	}