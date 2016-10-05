package stm;

/**
 * A file to define the whole hierarchy of STM layout in the database. As we generally only work as the STM, the distinction between STM and LTM can be
 * ignored, thus the name STMDefine are just synonym with databaseDefine.
 * These classes and hierarchies define the whole structure of the database.
 * LTM here means static data and will never be modified, while STM can be modified or even deleted at will.
 *
 * orientdb doesn't support classes with same name, so have to create extremely long fully qualified class name to maintain our hierarchy here.
 */
public class DBCN {
	/**
	 * E edge. Correspond to db's E class.
	 * Contains all the edge classes we will use.
	 */
	public static class E {
		public static final String cn = "E";
		public static final String parent = "E_parent";	//parent edge, used by convergence to identify its head node (other convergence) in a list.
		public static final String data = "E_data";

		public static final String requirement = "E_requirement";
		public static final String result = "E_result";
		public static final String prediction = "E_prediction";

		public static final String polyVal = "E_polyVal";

		//Processing and completed state of certain task.
		public static final String processing = "E_processing";
		//tell parent convergence that we had completed his task.
		public static final String completed = "E_completed";

		//job center uses this to identify their task's source general data.
		public static final String source = "E_source";

		//GCA link, uniquely identify-able. Basically just link from time grouping vertex (GCA).
		public static final String GCA = "E_GCA";

		//Previously completed vertex, for GCA Main use to identify and make edge to the last completed vertex.
		public static final String previous = "E_previous";

		//Occurrence of similar links that uses you as template.
		public static final String occurrence = "E_occurrence";

		//Session stores exp condition across multiple run.
		public static final String session = "E_session";
		//parentSession is for those exp created under this session, those exp doesn't own the session, only the original selected vertex own it.
		public static final String parentSession = "E_parentSession";
		//Used by session, to store all the vertex that had been modified during last session. Then use this data to calculate what
		//solution works and what doesn't then tinker the list. Modified means addition or deletion.
		public static final String modDuringSession = "E_modDuringSession";

		//For use within operation concerning 'session'.
		//expMain is the final experience output of particular session, convergenceMain is the grand beginning, the empty vertex that holds all
		//possible route for particular session.
		public static final String expMain = "E_expMain";
		public static final String convergenceMain = "E_convergenceMain";

		//Used by convergenceHead class only, it make an edge to a convergenceMain (head of the decision tree) during STISS.
		public static final String convergenceHead = "E_convergenceHead";

		//Record every whole system startup time, alongside with data about starting GCA, startup time, then before ending,
		//record the end time and make edge to the last GCA. Push this record to the global session
		//to mark it as current, so if any vertexes want to know these data, he can go to there.
		//These edges overlap each other, example this starting GCA = last end GCA, last end GCA = next new record's start GCA.
		//These 2 edge are made to connect to particular GCA instance for startup record purposes only.
		public static final String startupStartGCA = "E_startupStartGCA";
		public static final String startupEndGCA = "E_startupEndGCA";
	}

	/*
	 * NOTE when adding in new entry here, make sure to make relevant entry at LTM.general, GCA.general, EXP.general (which are some list).
	 * So your updates will be visible to the internal code without extra tinkering. Those are define files.
	 */

	/**
	 * V vertex. Correspond to db's V class.
	 * Node without cnExt means they will not have specific node uid tailing them.
	 */
	public static class V {
		public static final String cn = "V";

		/**
		 * For record purposes only! Record each full system startup, shutdown related data.
		 */
		public static class startup {
			public static final String cn = "V_startup";
			/**
			 * Current startup record, will be emptied and replaced at every new full system startup cycle.
			 */
			public static class current {
				public static final String cn = "V_startup_current";
			}
		}

		public static class globalDist {
			public static final String cn = "V_globalDist";

			public static class in {
				public static final String cn = "V_globalDist_in";
			}

			public static class out {
				public static final String cn = "V_globalDist_out";
			}
		}

		//Main worker node. The singular management thread that will manage its own set of crawlers and stm workers.
		public static class worker {
			public static final String cn = "V_worker";

			//Stores all the 30k storage registration information, whether the storage is registered, by who and other details.
			public static class storageRegister {
				public static final String cn = "V_worker_storageRegister";
			}

			//The address of registrar, where you can connect to it, and get a storage.
			public static class registrar {
				public static final String cn = "V_worker_registrar";
			}

			//Store the WMRequestListener's port and hostname.
			public static class WMRequestListener {
				public static final String cn = "V_worker_WMRequestListener";
			}

			//Then create 30k storage node here free for all worker to register.

		}	//end of worker

		//Real data are stored in here.
		public static class LTM {
			public static final String cn = "V_LTM";
			public static final String cnExt = "V_LTM_";

			//The actual full resolution raw data.
			public static class rawData {
				public static final String cn = "V_LTM_rawData";
				public static final String cnExt = "V_LTM_rawData_";

				//Add new device inside the dev class for both general and LTM class, then add relevant entry at the LTM.java so it can be
				//found by the system and be processed normally using default algorithm.
				public static class PI {
					public static final String cn = "V_LTM_rawData_PI";

					public static class dev {
						public static final String cn = "V_LTM_rawData_PI_dev";

						public static class camera1 {
							public static final String cn = "V_LTM_rawData_PI_dev_camera1";
						}

						public static class mic1 {
							public static final String cn = "V_LTM_rawData_PI_dev_mic1";
						}
					}
				}

				public static class POFeedback {
					public static final String cn = "V_LTM_rawData_POFeedback";
					//TODO_ Add more device as you wish at general and corresponding class at LTM side as well.
					public static class dev {
						public static final String cn = "V_LTM_rawData_POFeedback_dev";
						public static class motor1 {
							public static final String cn = "V_LTM_rawData_POFeedback_dev_motor1";
						}
						public static class motor2 {
							public static final String cn = "V_LTM_rawData_POFeedback_dev_motor2";
						}
						public static class motor3 {
							public static final String cn = "V_LTM_rawData_POFeedback_dev_motor3";
						}
						public static class motor4 {
							public static final String cn = "V_LTM_rawData_POFeedback_dev_motor4";
						}
						public static class speaker1 {
							public static final String cn = "V_LTM_rawData_POFeedback_dev_speaker1";
						}
					}
				}
			}

			public static class polyVal {
				public static final String cn = "V_LTM_polyVal";
				public static final String cnExt = "V_LTM_polyVal_";

				//Polyval calculated from raw data distribution.
				public static class rawData {
					public static final String cn = "V_LTM_polyVal_rawData";
					public static final String cnExt = "V_LTM_polyVal_rawData_";

					public static class visual {
						public static final String cn = "V_LTM_polyVal_rawData_visual";
						public static final String cnExt = "V_LTM_polyVal_rawData_visual_";
					}

					public static class audio {
						public static final String cn = "V_LTM_polyVal_rawData_audio";
						public static final String cnExt = "V_LTM_polyVal_rawData_audio_";
					}

					public static class movement {
						public static final String cn = "V_LTM_polyVal_rawData_movement";
						public static final String cnExt = "V_LTM_polyVal_rawData_movement_";
					}
				}
			}

			//Raw data generated/identified pattern.
			public static class rawDataICL {
				public static final String cn = "V_LTM_rawDataICL";
				public static final String cnExt = "V_LTM_rawDataICL_";

				public static class visual {
					public static final String cn = "V_LTM_rawDataICL_visual";
					public static final String cnExt = "V_LTM_rawDataICL_visual_";
				}

				public static class audio {
					public static final String cn = "V_LTM_rawDataICL_audio";
					public static final String cnExt = "V_LTM_rawDataICL_audio_";
				}

				public static class movement {
					public static final String cn = "V_LTM_rawDataICL_movement";
					public static final String cnExt = "V_LTM_rawDataICL_movement_";
				}
			}

			//Result of the completed grouped GCA vertex, where it contain edges to all the other vertexes and stuff happened at the moment.
			//This is the main GCA which groups all his child GCA's result.
			public static class GCAMain {
				public static final String cn = "V_LTM_GCAMain";

				//Here are the child GCA results.
				public static class rawData {
					public static final String cn = "V_LTM_GCAMain_rawData";
				}
				public static class rawDataICL {
					public static final String cn = "V_LTM_GCAMain_rawDataICL";
				}
				public static class exp {
					public static final String cn = "V_LTM_GCAMain_exp";
				}
				public static class POFeedbackGCA {
					public static final String cn = "V_LTM_GCAMain_POFeedbackGCA";
				}
			}

			//Exp form with 3 timeline, requirement, result and 1 more prediction data that you scheduled during the running and
			//creation of this exp.
			//exp here is expGeneral or expMain, both are the same with different name.
			public static class exp {
				public static final String cn = "V_LTM_exp";

				//Where each element edges has a frame time constant to show exactly when is the starting point.
				public static class requirement {
					public static final String cn = "V_LTM_exp_requirement";
				}
				public static class result {
					public static final String cn = "V_LTM_exp_result";
				}
				//Prediction is a aggregated timeline, excluding PO functions, include only the result to be expected at particular time.
				public static class prediction {
					public static final String cn = "V_LTM_exp_prediction";
				}
			}

		}	//end of LTM

		//General type vertex that stores crawler state and all other edges. Further classify them here by phrase.
		//General vertex can be considered LTM as well, it will stay there forever, but differences are it is mutable.
		//Eg RD1, RD2, RD3, GCA1, GCA2, ...
		public static class general {
			public static final String cn = "V_general";
			public static final String cnExt = "V_general_";

			//RD1 raw data.
			public static class rawData {
				public static final String cn = "V_general_rawData";
				public static final String cnExt = "V_general_rawData_";

				//Add new device inside the dev class for both general and LTM class, then add relevant entry at the LTM.java so it can be
				//found by the system and be processed normally using default algorithm.
				public static class PI {
					public static final String cn = "V_general_rawData_PI";

					public static class dev {
						public static final String cn = "V_general_rawData_PI_dev";

						public static class camera1 {
							public static final String cn = "V_general_rawData_PI_dev_camera1";
						}

						public static class mic1 {
							public static final String cn = "V_general_rawData_PI_dev_mic1";
						}
					}
				}

				//Physical output port feed back, use this to signify action. These data will be feed back in back the device itself for better accuracy,
				//but you may also simulate (imagine) what is happening at the PO side using ActionScheduler' output, but it might not be accurate.
				//Instruction on adding output (feedback) device, add it here at POFeedback, then go to STMServer raw data fetch from device section, add the
				//way how you get that data into the DB system, there are example there. Then add it to ActionScheduler to tell him about this new device,
				//and what data should be ported to that device if during data recreate.
				public static class POFeedback {
					public static final String cn = "V_general_rawData_POFeedback";
					//TODO_ Add more device as you wish at general and corresponding class at LTM side as well.
					public static class dev {
						public static final String cn = "V_general_rawData_POFeedback_dev";
						public static class motor1 {
							public static final String cn = "V_general_rawData_POFeedback_dev_motor1";
						}
						public static class motor2 {
							public static final String cn = "V_general_rawData_POFeedback_dev_motor2";
						}
						public static class motor3 {
							public static final String cn = "V_general_rawData_POFeedback_dev_motor3";
						}
						public static class motor4 {
							public static final String cn = "V_general_rawData_POFeedback_dev_motor4";
						}
						public static class speaker1 {
							public static final String cn = "V_general_rawData_POFeedback_dev_speaker1";
						}
					}
				}
			}

			//Raw data generated/identified pattern.
			public static class rawDataICL {
				public static final String cn = "V_general_rawDataICL";
				public static final String cnExt = "V_general_rawDataICL_";

				public static class visual {
					public static final String cn = "V_general_rawDataICL_visual";
					public static final String cnExt = "V_general_rawDataICL_visual_";
				}

				public static class audio {
					public static final String cn = "V_general_rawDataICL_audio";
					public static final String cnExt = "V_general_rawDataICL_audio_";
				}

				public static class movement {
					public static final String cn = "V_general_rawDataICL_movement";
					public static final String cnExt = "V_general_rawDataICL_movement_";
				}
			}

			//General vertexes for GCA result.
			public static class GCAMain {
				public static final String cn = "V_general_GCAMain";

				public static class rawData {
					public static final String cn = "V_general_GCAMain_rawData";
				}
				public static class rawDataICL {
					public static final String cn = "V_general_GCAMain_rawDataICL";
				}
				public static class exp {
					public static final String cn = "V_general_GCAMain_exp";
				}
				public static class POFeedbackGCA {
					public static final String cn = "V_general_GCAMain_POFeedbackGCA";
				}
			}

			//Hold temporary tasks that will converge soon during DWDM recursive simulation.
			//No index or organization required as they are kept as direct rid or edges thus retrieval by returning child will be instant.
			//Deploy a dedicated STM service to receive and update commits from crawlers to the relevant convergence vertex to avoid race condition.
			//It will also return the result accordingly when timeout reached.
			//Storage for completed and pending convergence operations in STM view are achieved by double entry in jobCenter, which will create a temporary
			//entry in database and another in jobCenter (in RID form). When the task is completed the temporary entry will be converted into LTM entry
			//and remain static for future references.
			//Main is the one containing other solution for a requirement, secondary is a singular solution to a problem.
			public static class convergenceMain {
				public static final String cn = "V_general_convergenceMain";
				public static final String cnExt = "V_general_convergenceMains_";
			}
			public static class convergenceSecondary {
				public static final String cn = "V_general_convergenceSecondary";
				public static final String cnExt = "V_general_convergenceSecondary_";
			}
			/*
			 * For statistical purposes only, used by visualization program to get and visualize the tree in order to audit what had happened
			 * at that particular moment and what was his intention.
			 */
			public static class convergenceHead {
				public static final String cn = "V_general_convergenceHead";
			}

			//exp here is expGeneral or expMain, both are the same with different name.
			public static class exp {
				public static final String cn = "V_general_exp";

				//Where each element edges has a frame time constant to show exactly when is the starting point.
				public static class requirement {
					public static final String cn = "V_general_exp_requirement";
				}
				public static class result {
					public static final String cn = "V_general_exp_result";
				}
				//Prediction is a aggregated timeline, excluding PO functions, include only the result to be expected at particular time.
				public static class prediction {
					public static final String cn = "V_general_exp_prediction";
				}
			}

			//Temporary session that are assigned with a unique ID based on its initiator's rid + currentTimeMilli, used on internal solution
			//generation and during exp creation as temporal holder for generally shared data such as parent original exp vertex edges
			//(impractical if allow each of them to edge to it as the sole meaning of needing it only once, and it is also a generate-able
			//data that can be reconstructed by traversing vertexes, but it takes a long time), serve as a cache for faster access and reduced
			//edges connection between data and allow generate on demand.
			public static class session {
				public static final String cn = "V_general_session";
			}
		}	//end of general


		//Temporary vertexes that will be eliminated soon after the completion of their process. Store intermediate helper data.
		public static class temp {
			public static final String cn = "V_temp";
			public static final String cnExt = "V_temp";

			public static class ICLPatternFeedback {
				public static final String cn = "V_temp_ICLPatternFeedback";
				public static class rawData {
					public static final String cn = "V_temp_ICLPatternFeedback_rawData";
					public static class visual {
						public static final String cn = "V_temp_ICLPatternFeedback_rawData_visual";
					}
					public static class audio {
						public static final String cn = "V_temp_ICLPatternFeedback_rawData_audio";
					}
					public static class movement {
						public static final String cn = "V_temp_ICLPatternFeedback_rawData_movement";
					}
				}
			}
		}

		//External interface, software or hardware level, anything.
		public static class extInterface {
			//Create an instance of device to add new device, then within it, check type and version to get the relevant entries. Group the relevant
			//device together. Input or output is specified within the device itself. Interface type like visual, audio or movement.
			public static final String cn = "V_extInterface";
			public static final String cnExt = "V_extInterface_";

			//hardware
			public static class hw {
				public static final String cn = "V_extInterface_hw";

				public static class camera {
					public static final String cn = "V_extInterface_hw_camera";

					//here lies the address to visual input.
					public static class cam1 {
						public static final String cn = "V_extInterface_hw_camera_cam1";
					}
				}

				public static class mic {
					public static final String cn = "V_extInterface_hw_mic";
					//Stores the address to audio input.
					public static class mic1 {
						public static final String cn = "V_extInterface_hw_mic_mic1";
					}
				}

				public static class controller {
					public static final String cn = "V_extInterface_hw_controller";
					public static class rpi {
						public static final String cn = "V_extInterface_hw_controller_rpi";
					}
				}
			}

			//software, like emulated keyboard.
			public static class sw {
				public static final String cn = "V_extInterface_sw";
				public static final String cnExt = "V_extInterface_sw_";
			}
		}

		//Automatically select the device to output, make actual contact with the real external device by posting instruction at the predefined time.
		//Uses the same time system of WM.
		public static class actionScheduler {
			public static final String cn = "V_actionScheduler";
			public static class config {
				public static final String cn = "V_actionScheduler_config";
			}
			//Add the action (process) vertex and a time data. Then you shall reuse this RID to refer to your action. Make double entry at jobcenter and
			//here, use the STMClient's addAction function.
			public static class addAction {
				public static final String cn = "V_actionScheduler_addAction";
			}
			public static class allAction {
				public static final String cn = "V_actionScheduler_allAction";
			}
		}

		//Working Memory. You might want to migrate this into a separate DB so you can make use of in memory feature for faster IO.
		public static class WM {
			public static final String cn = "V_WM";
			//Construct a virtual timeline system, then it will tick tock tick tock update and migrate elements backward.
			//Usable by specify future frame how much.
			public static class timeline {
				public static final String cn = "V_WM_timeline";
				//actual timeline.
				//All the data ported in will be static, with a time reference, then you can calculate which one of them you want by giving time offset.
				//It will be assigned a time point reference, then updated against the latest real frame data.
				public static class addPrediction {
					public static final String cn = "V_WM_timeline_addPrediction";
				}
				//A temporary vertex storage to store references to the actual 'process' data vertex, here store a vertex that have an edge to
				//the actual 'process' data vertex and the time to execute, after completion, it will be removed.
				public static class addPhysicalOutput {
					public static final String cn = "V_WM_timeline_addPhysicalOutput";
				}

				//Store erroneous route RID, so WM workers can see that route had raise an exception, thus we will wipe out all of the
				//prediction and actions scheduled for that route.
				public static class errorRouteRid {
					public static final String cn = "V_WM_timeline_errorRouteRid";
				}
			}
		}

		//Store every task's detail for future references.
		public static class taskDetail {
			public static final String cn = "V_taskDetail";
		}

		//Every crawler must register themselves under one of these job sector in order to enroll and receive job feeds to their local storage.
		//Crawlers register themselves by adding their RID under any of the registered class. Then he will receive job feeds when there is job
		//from the neighbouring task list by STM update loop.
		//If crawler dies, its pending tasks will be migrated from its local storage back into the respective task list.
		//Config part store how much more power required or excess in order to recruit or migrate crawler to other places in need.
		//All the task properties have a additional _ (colon) to allow it to switch to another storage globally when new task arrive so we can
		//avoid race condition and data loss due to negligence.
		//NOTE: There will always be only 3 element, in the same order: config, task, worker.
		public static class jobCenter {
			public static final String cn = "V_jc";

			/*
			 * Use the final revision of task model. targetVertex becomes task vertex, and contain edge to the actual general vertex.
			 * 3 storage for job, namely new, processing and completed.
			 * new is where all task begin, then it will be assigned a worker and migrate to processing.
			 * At processing it will be monitored and updated, if error occurs, he can be migrated to other node.
			 * When finished it will be migrated again to completed and stay there forever.
			 */

			//All applicable operation available for crawler are to be registered here. Jobs for general crawlers
			public static class crawler {
				public static final String cn = "V_jc_crawler";
//				public static final String cnExt = "V_jc_crawler_";

				//RD1, distCacl (distribution calculation and return feed to global distribution repository to calculate next new global dist)
				public static class rawDataDistCacl {
					public static final String cn = "V_jc_crawler_rawDataDistCacl";
					//TODO: Might add more type of task here differentiated by raw data type for better concurrency.
					//Now we uses 1 function to that support all type of raw data computation at once.
					public static class task {
						public static final String cn = "V_jc_crawler_rawDataDistCacl_task";
					}
					public static class processing {
						public static final String cn = "V_jc_crawler_rawDataDistCacl_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_crawler_rawDataDistCacl_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_crawler_rawDataDistCacl_worker";
					}
				}

				public static class rawDataICL {
					public static final String cn = "V_jc_crawler_rawDataICL";

					public static class task {
						public static final String cn = "V_jc_crawler_rawDataICL_task";
					}
					public static class processing {
						public static final String cn = "V_jc_crawler_rawDataICL_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_crawler_rawDataICL_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_crawler_rawDataICL_worker";
					}
				}

				//Below are DM operations.
				//Solution Tree Initial Structure Setup (STISS), filter demand once and setup the initial tree structure.
				public static class STISS {
					public static final String cn = "V_jc_crawler_STISS";

					public static class task {
						public static final String cn = "V_jc_crawler_STISS_task";
					}
					public static class processing {
						public static final String cn = "V_jc_crawler_STISS_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_crawler_STISS_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_crawler_STISS_worker";
					}
				}

				//Recursive Solution Generation(RSG), generate solutions for specified demand recursively.
				public static class RSG {
					public static final String cn = "V_jc_crawler_RSG";

					public static class task {
						public static final String cn = "V_jc_crawler_RSG_task";
					}
					public static class processing {
						public static final String cn = "V_jc_crawler_RSG_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_crawler_RSG_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_crawler_RSG_worker";
					}
				}

				//Secondary Convergence Check Requirement Scheduled (SCCRS), check whether requirement already exist, existed or scheduled to exist.
				public static class SCCRS {
					public static final String cn = "V_jc_crawler_SCCRS";

					public static class task {
						public static final String cn = "V_jc_crawler_SCCRS_task";
					}
					public static class processing {
						public static final String cn = "V_jc_crawler_SCCRS_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_crawler_SCCRS_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_crawler_SCCRS_worker";
					}
				}

				//Amid Convergence Tree Generation Decide Route (ACTGDR), select route to generate amid tree generation to reduce computing power
				//requirement, and to prevent calculating to whole tree, this will limit it to calculate DM logic applied and approved part only.
				//ACTGDR and RSGFSB are the only 2 DM that concerns about solution tree structural generation and route selection.
				public static class ACTGDR {
					public static final String cn = "V_jc_crawler_ACTGDR";

					public static class task {
						public static final String cn = "V_jc_crawler_ACTGDR_task";
					}
					public static class processing {
						public static final String cn = "V_jc_crawler_ACTGDR_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_crawler_ACTGDR_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_crawler_ACTGDR_worker";
					}
				}

				//RSG Failed Switch Branch (RSGFSB), when RSG failed to generate a solution, it will post its given mainConvergence to RSGFSB,
				//then invalidate its branch and select next best branch if available, else traverse up the solution tree once more and
				//so on to find applicable branch, if run out of branch without solution, declare failure and do nothing OR do whatever you had.
				//ACTGDR and RSGFSB are the only 2 DM that concerns about solution tree structural generation and route selection.
				public static class RSGFSB {
					public static final String cn = "V_jc_crawler_RSGFSB";

					public static class task {
						public static final String cn = "V_jc_crawler_RSGFSB_task";
					}
					public static class processing {
						public static final String cn = "V_jc_crawler_RSGFSB_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_crawler_RSGFSB_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_crawler_RSGFSB_worker";
					}
				}

				//Recursive Execute Route And Update Prediction (RERAUP).
				public static class RERAUP {
					public static final String cn = "V_jc_crawler_RERAUP";

					public static class task {
						public static final String cn = "V_jc_crawler_RERAUP_task";
					}
					public static class processing {
						public static final String cn = "V_jc_crawler_RERAUP_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_crawler_RERAUP_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_crawler_RERAUP_worker";
					}
				}
			}

			//STM internal distributed management task registration.  Jobs for STM management workers.
			public static class STM {
				public static final String cn = "V_jc_STM";

				//Update global distribution from by calculating data by averaging all operation provided polyVals.
				public static class globalDistUpdate {
					public static final String cn = "V_jc_STM_globalDistUpdate";

					public static class task {
						public static final String cn = "V_jc_STM_globalDistUpdate_task";
					}
					public static class processing {
						public static final String cn = "V_jc_STM_globalDistUpdate_processing";
					}
					public static class completed {
						public static final String cn = "V_jc_STM_globalDistUpdate_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_STM_globalDistUpdate_worker";
					}
				}


				//How it works: Create a dummy vertex with edge to the original vertex and store the dummy at here in task storage by subscriber,
				//then STM work is to group them together.
				//GCAMain will group all child type GCA entries together.
				//NOTE: GCA operation doesn't have intermediate phrase processing, as it is only assigned to 1 task. And its task is the target
				//of grouping, in bulk, each entry 1 vertex, therefore it make no sense to forward them to 'processing' phrase as it is not
				//a conventional single task detail instruction vertex. Completed vertex exist to store who processed the data.
				public static class GCAMain {
					public static final String cn = "V_jc_STM_GCAmain";

					public static class task {
						public static final String cn = "V_jc_STM_GCAmain_task";
					}
					public static class completed {
						public static final String cn = "V_jc_STM_GCAmain_completed";
					}
					public static class worker {
						public static final String cn = "V_jc_STM_GCAmain_worker";
					}
					//A temporary storage to store previously, last completed GCAMain, so we can add edge to him.
					public static class previous {
						public static final String cn = "V_jc_STM_GCAmain_previous";
					}

					//Data is the absolute raw data, freshly received from device.
					public static class rawData {
						public static final String cn = "V_jc_STM_GCAmain_rawData";
						public static class task {
							public static final String cn = "V_jc_STM_GCAmain_rawData_task";
						}
						public static class completed {
							public static final String cn = "V_jc_STM_GCAmain_rawData_completed";
						}
						public static class worker {
							public static final String cn = "V_jc_STM_GCAmain_rawData_worker";
						}
					}
					//Data is the ICL-ed once raw data, which consist of patterns.
					public static class rawDataICL {
						public static final String cn = "V_jc_STM_GCAmain_rawDataICL";
						public static class task {
							public static final String cn = "V_jc_STM_GCAmain_rawDataICL_task";
						}
						public static class completed {
							public static final String cn = "V_jc_STM_GCAmain_rawDataICL_completed";
						}
						public static class worker {
							public static final String cn = "V_jc_STM_GCAmain_rawDataICL_worker";
						}
					}
					public static class exp {
						public static final String cn = "V_jc_STM_GCAmain_exp";
						public static class task {
							public static final String cn = "V_jc_STM_GCAmain_exp_task";
						}
						public static class completed {
							public static final String cn = "V_jc_STM_GCAmain_exp_completed";
						}
						public static class worker {
							public static final String cn = "V_jc_STM_GCAmain_exp_worker";
						}
					}
				}
			}
		}	//end of job center

		public static class devnull {
			public static final String cn = "V_devnull";
		}

		//Store all the logged data.
		public static class log {
			public static final String cn = "V_log";
		}

		//Credential for each log invoker, each worker thread should have their own log credential created.
		public static class logCredential {
			public static final String cn = "V_logCredential";
		}

		//Feedback information to console.
		public static class consoleFeedback {
			public static final String cn = "V_consoleFeedback";
		}
	}	//end of V

	//Index classes.
	public static class index {
		public static final String cn = "index";

		//WM index, used to tell what data is currently valid in the WM STM scope.
		public static class WMTimeRanIndex {
			public static final String cn = "index_WMTimeRanIndex";
		}
		public static class WMPrecisionRateIndex {
			public static final String cn = "index_WMPrecisionRateIndex";
		}
	}
}
