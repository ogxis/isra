package linkProperty;

/**
 * Define all the possible property type a vertex may contain.
 * Link Property (LP).
 * http://stackoverflow.com/questions/3978654/best-way-to-create-enum-of-strings
 * Uses string literal instead of .name() to avoid obfuscation error where it inadvertently changes the name, causing weird behavior.
 */
public class LinkProperty {
	public enum LP {
		data("data"),

		//ICL data vertex.
		polyVal("polyVal"),
		globalDist("globalDist"),

		//Used by exp, amount of time it had been ran.
		timeRan("timeRan"),

		//Crawler state directive. Use with CrawlerDefine class which contain all the possible predefined state.
		crawlerState("crawlerState"),

		//Visual ICL parameter
		imgWidth("imgWidth"),
		imgHeight("imgHeight"),
		imgFileFormat("imgFileFormat"),  //encoding format, eg png, gif.
		imgType("imgType"),  //Mat recognized type. eg 8UC1 16UC3
		imgX("imgX"),  //image original X coordinate on the source.
		imgY("imgY"),  //image original Y coordinate on the source.

		//Audio ICL parameter
		audioFrameCount("audioFrameCount"),
		audioFrameRate("audioFrameRate"),
		audioUBound("audioUBound"),
		audioLBound("audioLBound"),
		audioChannel("audioChannel"),
		audioFileFormat("audioFileFormat"),  //encoding format, eg wav, mp3.
		//audio pattern's absolute timestamp, in order to pin point the exact location of where it originates from and at what position.
		audioAbsTimestamp("audioAbsTimestamp"),

		//Motor ICL parameter
		motorState("motorState"),  //forward / backward
		motorPWM("motorPWM"),  //speed control 0~100% then hardware modulated at device premises.
		motorPosition("motorPosition"),  //for stepper motor, the gear position.
		motorID("motorID"),

		//Used by convergence vertex.
		totalSolutionSize("totalSolutionSize"),
		remainingSolutionToBeCompleted("remainingSolutionToBeCompleted"),
		originalOrdering("originalOrdering"),

		crawlerUid("crawlerUid"),

		averageFailRateExcludeFuture("averageFailRateExcludeFuture"),

		//Used by DMRoute(Selected route to execute from solution tree).
		remainingStep("remainingStep"),

		//Used in startupSoft
		version("version"),
		workerList("workerList"),

		//STM uses them to assign tasks.
		currentStorageIndex("currentStorageIndex"),
		totalStorageCount("totalStorageCount"),
		nodeUID("nodeUID"),

		taskTypeIsSTM("taskTypeIsSTM"),  //either STM or crawler.
		taskName("taskName"),  //name of specific task.

		//WM stores his index data as a ridList.
		ridList("ridList"),

		//This is for error recovery, so we can know which convergence vertex is responsible for the prediction, then we can traverse from it to
		//get other route(branch) to execute.
		actualConvergenceVertexRid("actualConvergenceVertexRid"),
		//For error recovery as well, use this to halt actions if error occurs.
		relatedPhysicalOutputRidList("relatedPhysicalOutputRidList"),
		dataVertexRid("dataVertexRid"),
		decidedRouteRid("decidedRouteRid"),
		ICLPatternRidsList("ICLPatternRidsList"),

		//Used by storage registrar.
		isRegistered("isRegistered"),
		registrant("registrant"),
		storageId("storageId"),
		lastPingTime("lastPingTime"),
		pingLatency("pingLatency"),
		duration("duration"),
		timeStamp("timeStamp"),

		//Used by storage registrar.
		hostName("hostName"),
		port("port"),

		//A flag to indicate the state of each individual exp, their current state. Read EXPSTATE.java for all available state.
		expState("expState"),

		//The rate of success among multiple prediction and reality pair. If it is high, means the solution is precise.
		precisionRate("precisionRate"),

		//Equivalent to timeRan of certain vertex, the calculated occurrence edge count that had ultimately contributed to precisionRate calculation.
		//To understand the whole design of this PR final calculation scheme, visit 29-6-16.
		occurrenceCountPR("occurrenceCountPR"),

		//Depth means the depth of exp, exp can be nested, thus deeper the depth, more complex the action is.
		depth("depth"),

		//Used by mainConvergence during crawler's solution tree generation for DM to decide which route to proceed.
		timeRequirementList("timeRequirementList"),
		timeRanList("timeRanList"),
		polyValList("polyValList"),
		precisionRateList("precisionRateList"),
		requirementList("requirementList"),
		//Store which path he has chosen, path will be one of main's secondary vertex, and we store it as an index only not actual data.
		//The index is based on the secondary convergence original ordering.
		selectedSolutionIndex("selectedSolutionIndex"),
		//Stores the sorted solution index list data where its head is the best solution, and so on.
		//The index is based on the secondary convergence original ordering.
		sortedSolutionIndexList("sortedSolutionIndexList"),

		//Store what individual requirement data field had failed for DM to know, empty if everything is running smooth.
		SCCRSFailedIndexList("SCCRSFailedIndexList"),
		//For Secondary vertex use, to record all the requirement OR result he pair against reality that had failed at the mean time only.
		//The index is the actual requirement or result's internal data's original ordering, not the external casing (expRequirementGeneral) but the
		//expanded once version of that, which is the internal requirement it carries.
		realityPairFailedIndexList("realityPairFailedIndexList"),

		//Used by session. banList is the vertex rids list that will not be considered during exp and route creation. greenList is the things confirmed.
		//Every modification will be stored, if it failed to add precision, add to banlist, else add to greenlist.
		banList("banList"),
		greenList("greenList"),

		//Store the last, best precision rate and the best precision rate's RID for easier access. Used by session.
		lastPrecisionRate("lastPrecisionRate"),
		bestPrecisionRate("bestPrecisionRate"),
		bestPrecisionRateRid("bestPrecisionRateRid"),

		//Used when transferring new prediction task to WM from crawler.
		sessionRid("sessionRid"),

		startTimeOffset("startTimeOffset"),

		SCCRSCompletedSize("SCCRSCompletedSize"),

		//Record statistical data for each full system startup, for archival analysis purposes only. (Doesn't interfere with main flow)
		startupStartTime("startupStartTime"),
		startupEndTime("startupEndTime"),
		/**
		 * Precision rate of this particular session, calculated after full system shutdown, by third party process whom is not directly
		 * related to main's code or process.
		 * Calculated by traversing each decision tree, if that tree passes PaRc, then mark it as pass, else fail.
		 * Sum all the pass then divide it by pass + fail (total), then multiply by 100 to convert it to percentage (polyVal representation).
		 * Initialized to -1 during creation.
		 */
		startupPrecisionRate("startupPrecisionRate"),
		/**
		 * Total number of element that used as base for the precision rate, used to reverse calculate the passed element count after
		 * precision rate is available.
		 */
		startupPrecisionRateElementCount("startupPrecisionRateElementCount"),
		/**
		 * Number of GCAs for this session.
		 */
		startupGCALength("startupGCALength"),
		/**
		 * Used by analytic tool, set by WM, when whole decision tree is failed, so we don't have to dive in further during analysis.
		 */
		PaRcPassed("PaRcPassed"),

		/**
		 * Used by WM index system to manage clustered index for better read write delete performance.
		 */
		initialized("initialized"),
		maxClusterCount("maxClusterCount"),
		elementPerCluster("elementPerCluster"),
		currentInsertClusterIndex("currentInsertClusterIndex"),
		currentInsertClusterElementCount("currentInsertClusterElementCount"),
		;

		//Function to convert enum to string representations.
		private final String text;

		private LP(final String text) {
	        this.text = text;
	    }

	    @Override
	    public String toString() {
	        return text;
	    }
	}
}