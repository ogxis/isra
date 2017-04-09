---
layout: default
title: Memory Structure
permalink: /how-it-works/memory-structure-for-each/
---

# Memory Structure Type List

			//All these classes correspond to all DBCN classes.
			//Get a graph instance and setup the whole DB initial hierarchy.
			txGraph.createEdgeType(DBCN.E.parent, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.data, DBCN.E.cn);
			//These 3 edge are the only edge with property, to record the start time offset for operation timing purposes.
			EdgeType requirement = txGraph.createEdgeType(DBCN.E.requirement, DBCN.E.cn);
			requirement.createProperty(LP.startTimeOffset, PropertyType.LONG);
			EdgeType result = txGraph.createEdgeType(DBCN.E.result, DBCN.E.cn);
			result.createProperty(LP.startTimeOffset, PropertyType.LONG);
			EdgeType prediction = txGraph.createEdgeType(DBCN.E.prediction, DBCN.E.cn);
			prediction.createProperty(LP.startTimeOffset, PropertyType.LONG);

			txGraph.createEdgeType(DBCN.E.polyVal, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.processing, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.completed, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.source, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.GCA, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.previous, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.occurrence, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.session, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.parentSession, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.modDuringSession, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.expMain, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.convergenceMain, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.startupStartGCA, DBCN.E.cn);
			txGraph.createEdgeType(DBCN.E.startupEndGCA, DBCN.E.cn);

			//For vertex property, if doesn't specify, means it doesn't have property. For every property, we skipped its initial DBCN_V as it is redundant.
			//Store the initial data for each full system startup for statistical purposes.
			VertexType startup = txGraph.createVertexType(DBCN.V.startup.cn, DBCN.V.cn);
			startup.createProperty(LP.startupStartTime, PropertyType.LONG);
			startup.createProperty(LP.startupEndTime, PropertyType.LONG);
			startup.createProperty(LP.startupPrecisionRate, PropertyType.DOUBLE);
			startup.createProperty(LP.startupPrecisionRateElementCount, PropertyType.INTEGER);
			startup.createProperty(LP.startupGCALength, PropertyType.INTEGER);

			txGraph.createVertexType(DBCN.V.startup.current.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.globalDist.cn, DBCN.V.cn);
			VertexType globalDist_in = txGraph.createVertexType(DBCN.V.globalDist.in.cn, DBCN.V.cn);
			globalDist_in.createProperty(LP.data, PropertyType.DOUBLE);
			VertexType globalDist_out = txGraph.createVertexType(DBCN.V.globalDist.out.cn, DBCN.V.cn);
			globalDist_out.createProperty(LP.data, PropertyType.DOUBLE);

			VertexType worker = txGraph.createVertexType(DBCN.V.worker.cn, DBCN.V.cn);
			worker.createProperty(LP.nodeUID, PropertyType.STRING);	//Stores registered computing node id.
			worker.createProperty(LP.workerList, PropertyType.STRING);	//yml file that stores all main worker's sub worker list.
			worker.createProperty(LP.storageId, PropertyType.STRING);	//The command storage id.

			VertexType worker_storageRegister = txGraph.createVertexType(DBCN.V.worker.storageRegister.cn, DBCN.V.cn);
			worker_storageRegister.createProperty(LP.storageId, PropertyType.STRING);
			worker_storageRegister.createProperty(LP.isRegistered, PropertyType.BOOLEAN);
			worker_storageRegister.createProperty(LP.registrant, PropertyType.STRING);
			worker_storageRegister.createProperty(LP.lastPingTime, PropertyType.LONG);
			worker_storageRegister.createProperty(LP.pingLatency, PropertyType.LONG);
			worker_storageRegister.createProperty(LP.timeStamp, PropertyType.LONG);

			//Stores the connection detail, how can you connect to him.
			VertexType worker_registrar = txGraph.createVertexType(DBCN.V.worker.registrar.cn, DBCN.V.cn);
			worker_registrar.createProperty(LP.hostName, PropertyType.STRING);
			worker_registrar.createProperty(LP.port, PropertyType.INTEGER);

			VertexType worker_WMRequestListener = txGraph.createVertexType(DBCN.V.worker.WMRequestListener.cn, DBCN.V.cn);
			worker_WMRequestListener.createProperty(LP.nodeUID, PropertyType.STRING);
			worker_WMRequestListener.createProperty(LP.hostName, PropertyType.STRING);
			worker_WMRequestListener.createProperty(LP.port, PropertyType.INTEGER);

			txGraph.createVertexType(DBCN.V.LTM.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.rawData.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.rawData.PI.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.rawData.PI.dev.cn, DBCN.V.cn);
			VertexType LTM_rawData_PI_dev_camera1 = txGraph.createVertexType(DBCN.V.LTM.rawData.PI.dev.camera1.cn, DBCN.V.cn);
			LTM_rawData_PI_dev_camera1.createProperty(LP.data, PropertyType.BINARY);
			VertexType LTM_rawData_PI_dev_mic1 = txGraph.createVertexType(DBCN.V.LTM.rawData.PI.dev.mic1.cn, DBCN.V.cn);
			LTM_rawData_PI_dev_mic1.createProperty(LP.data, PropertyType.BINARY);

			txGraph.createVertexType(DBCN.V.LTM.rawData.POFeedback.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.rawData.POFeedback.dev.cn, DBCN.V.cn);
			VertexType LTM_rawData_POFeedback_dev_motor1 = txGraph.createVertexType(DBCN.V.LTM.rawData.POFeedback.dev.motor1.cn, DBCN.V.cn);
			LTM_rawData_POFeedback_dev_motor1.createProperty(LP.data, PropertyType.DOUBLE);
			VertexType LTM_rawData_POFeedback_dev_motor2 = txGraph.createVertexType(DBCN.V.LTM.rawData.POFeedback.dev.motor2.cn, DBCN.V.cn);
			LTM_rawData_POFeedback_dev_motor2.createProperty(LP.data, PropertyType.DOUBLE);
			VertexType LTM_rawData_POFeedback_dev_motor3 = txGraph.createVertexType(DBCN.V.LTM.rawData.POFeedback.dev.motor3.cn, DBCN.V.cn);
			LTM_rawData_POFeedback_dev_motor3.createProperty(LP.data, PropertyType.DOUBLE);
			VertexType LTM_rawData_POFeedback_dev_motor4 = txGraph.createVertexType(DBCN.V.LTM.rawData.POFeedback.dev.motor4.cn, DBCN.V.cn);
			LTM_rawData_POFeedback_dev_motor4.createProperty(LP.data, PropertyType.DOUBLE);
			VertexType LTM_rawData_POFeedback_dev_speaker1 = txGraph.createVertexType(DBCN.V.LTM.rawData.POFeedback.dev.speaker1.cn, DBCN.V.cn);
			LTM_rawData_POFeedback_dev_speaker1.createProperty(LP.data, PropertyType.BINARY);

			txGraph.createVertexType(DBCN.V.LTM.polyVal.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.polyVal.rawData.cn, DBCN.V.cn);
			VertexType LTM_polyVal_rawData_visual = txGraph.createVertexType(DBCN.V.LTM.polyVal.rawData.visual.cn, DBCN.V.cn);
			LTM_polyVal_rawData_visual.createProperty(LP.data, PropertyType.DOUBLE);
			VertexType LTM_polyVal_rawData_audio = txGraph.createVertexType(DBCN.V.LTM.polyVal.rawData.audio.cn, DBCN.V.cn);
			LTM_polyVal_rawData_audio.createProperty(LP.data, PropertyType.DOUBLE);
			VertexType LTM_polyVal_rawData_movement = txGraph.createVertexType(DBCN.V.LTM.polyVal.rawData.movement.cn, DBCN.V.cn);
			LTM_polyVal_rawData_movement.createProperty(LP.data, PropertyType.DOUBLE);

			txGraph.createVertexType(DBCN.V.LTM.rawDataICL.cn, DBCN.V.cn);
			VertexType LTM_rawDataICL_visual = txGraph.createVertexType(DBCN.V.LTM.rawDataICL.visual.cn, DBCN.V.cn);
			LTM_rawDataICL_visual.createProperty(LP.data, PropertyType.BINARY);
			LTM_rawDataICL_visual.createProperty(LP.imgX, PropertyType.INTEGER);
			LTM_rawDataICL_visual.createProperty(LP.imgY, PropertyType.INTEGER);
			VertexType LTM_rawDataICL_audio = txGraph.createVertexType(DBCN.V.LTM.rawDataICL.audio.cn, DBCN.V.cn);
			LTM_rawDataICL_audio.createProperty(LP.data, PropertyType.BINARY);
			LTM_rawDataICL_audio.createProperty(LP.audioAbsTimestamp, PropertyType.LONG);
			//Temporary useless as our current movement data doesn't go through ICL phrase but direct into GCA phrase as it has nothing to pair with.
			txGraph.createVertexType(DBCN.V.LTM.rawDataICL.movement.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.LTM.GCAMain.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.GCAMain.rawData.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.GCAMain.rawDataICL.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.GCAMain.POFeedbackGCA.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.GCAMain.exp.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.LTM.exp.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.exp.requirement.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.exp.result.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.LTM.exp.prediction.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.general.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.general.rawData.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.general.rawData.PI.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.general.rawData.PI.dev.cn, DBCN.V.cn);
			VertexType general_rawData_PI_dev_camera1 = txGraph.createVertexType(DBCN.V.general.rawData.PI.dev.camera1.cn, DBCN.V.cn);
			general_rawData_PI_dev_camera1.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_rawData_PI_dev_mic1 = txGraph.createVertexType(DBCN.V.general.rawData.PI.dev.mic1.cn, DBCN.V.cn);
			general_rawData_PI_dev_mic1.createProperty(LP.polyVal, PropertyType.DOUBLE);

			txGraph.createVertexType(DBCN.V.general.rawData.POFeedback.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.general.rawData.POFeedback.dev.cn, DBCN.V.cn);
			VertexType general_rawData_POFeedback_dev_motor1 = txGraph.createVertexType(DBCN.V.general.rawData.POFeedback.dev.motor1.cn, DBCN.V.cn);
			general_rawData_POFeedback_dev_motor1.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_rawData_POFeedback_dev_motor2 = txGraph.createVertexType(DBCN.V.general.rawData.POFeedback.dev.motor2.cn, DBCN.V.cn);
			general_rawData_POFeedback_dev_motor2.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_rawData_POFeedback_dev_motor3 = txGraph.createVertexType(DBCN.V.general.rawData.POFeedback.dev.motor3.cn, DBCN.V.cn);
			general_rawData_POFeedback_dev_motor3.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_rawData_POFeedback_dev_motor4 = txGraph.createVertexType(DBCN.V.general.rawData.POFeedback.dev.motor4.cn, DBCN.V.cn);
			general_rawData_POFeedback_dev_motor4.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_rawData_POFeedback_dev_speaker1 = txGraph.createVertexType(DBCN.V.general.rawData.POFeedback.dev.speaker1.cn, DBCN.V.cn);
			general_rawData_POFeedback_dev_speaker1.createProperty(LP.polyVal, PropertyType.DOUBLE);

			txGraph.createVertexType(DBCN.V.general.rawDataICL.cn, DBCN.V.cn);
			VertexType general_rawDataICL_visual = txGraph.createVertexType(DBCN.V.general.rawDataICL.visual.cn, DBCN.V.cn);
			general_rawDataICL_visual.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_rawDataICL_audio = txGraph.createVertexType(DBCN.V.general.rawDataICL.audio.cn, DBCN.V.cn);
			general_rawDataICL_audio.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_rawDataICL_movement = txGraph.createVertexType(DBCN.V.general.rawDataICL.movement.cn, DBCN.V.cn);
			general_rawDataICL_movement.createProperty(LP.polyVal, PropertyType.DOUBLE);

			VertexType general_GCAMain = txGraph.createVertexType(DBCN.V.general.GCAMain.cn, DBCN.V.cn);
			general_GCAMain.createProperty(LP.timeStamp, PropertyType.LONG);
			general_GCAMain.createProperty(LP.polyVal, PropertyType.DOUBLE);
			txGraph.createVertexType(DBCN.V.general.GCAMain.rawData.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.general.GCAMain.rawDataICL.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.general.GCAMain.POFeedbackGCA.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.general.GCAMain.exp.cn, DBCN.V.cn);

			VertexType general_convergenceMain = txGraph.createVertexType(DBCN.V.general.convergenceMain.cn, DBCN.V.cn);
			general_convergenceMain.createProperty(LP.timeRequirementList, PropertyType.BINARY);
			general_convergenceMain.createProperty(LP.timeRanList, PropertyType.BINARY);
			general_convergenceMain.createProperty(LP.polyValList, PropertyType.BINARY);
			general_convergenceMain.createProperty(LP.precisionRateList, PropertyType.BINARY);
			general_convergenceMain.createProperty(LP.originalOrdering, PropertyType.INTEGER);
			general_convergenceMain.createProperty(LP.totalSolutionSize, PropertyType.INTEGER);
			general_convergenceMain.createProperty(LP.requirementList, PropertyType.BINARY);
			general_convergenceMain.createProperty(LP.selectedSolutionIndex, PropertyType.INTEGER);
			general_convergenceMain.createProperty(LP.sortedSolutionIndexList, PropertyType.BINARY);
			general_convergenceMain.createProperty(LP.SCCRSCompletedSize, PropertyType.INTEGER);

			VertexType general_convergenceSecondary = txGraph.createVertexType(DBCN.V.general.convergenceSecondary.cn, DBCN.V.cn);
			general_convergenceSecondary.createProperty(LP.originalOrdering, PropertyType.INTEGER);
			general_convergenceSecondary.createProperty(LP.realityPairFailedIndexList, PropertyType.BINARY);
			general_convergenceSecondary.createProperty(LP.totalSolutionSize, PropertyType.INTEGER);
			general_convergenceSecondary.createProperty(LP.remainingSolutionToBeCompleted, PropertyType.INTEGER);

			//It will hold an edge convergenceHead to the head of a decision tree, where its underlying type will be a convergenceMain
			//created during STISS.
			VertexType general_convergenceHead = txGraph.createVertexType(DBCN.V.general.convergenceHead.cn, DBCN.V.cn);
			//Timestamp is the moment of creation of this vertex, NOT the end of this tree.
			general_convergenceHead.createProperty(LP.timeStamp, PropertyType.LONG);
			//Set after WM PaRc completes, if whole tree fail, then this will be set to false. Default is true.
			general_convergenceHead.createProperty(LP.PaRcPassed, PropertyType.BOOLEAN);

			VertexType general_exp = txGraph.createVertexType(DBCN.V.general.exp.cn, DBCN.V.cn);
			//Read EXPSTATE.java for all available state.
			general_exp.createProperty(LP.expState, PropertyType.INTEGER);
			general_exp.createProperty(LP.polyVal, PropertyType.DOUBLE);
			general_exp.createProperty(LP.timeStamp, PropertyType.LONG);
			general_exp.createProperty(LP.duration, PropertyType.LONG);
			general_exp.createProperty(LP.precisionRate, PropertyType.DOUBLE);
			general_exp.createProperty(LP.occurrenceCountPR, PropertyType.LONG);
			general_exp.createProperty(LP.depth, PropertyType.INTEGER);
			VertexType general_exp_requirement = txGraph.createVertexType(DBCN.V.general.exp.requirement.cn, DBCN.V.cn);
			general_exp_requirement.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_exp_result = txGraph.createVertexType(DBCN.V.general.exp.result.cn, DBCN.V.cn);
			general_exp_result.createProperty(LP.polyVal, PropertyType.DOUBLE);
			VertexType general_exp_prediction = txGraph.createVertexType(DBCN.V.general.exp.prediction.cn, DBCN.V.cn);
			general_exp_prediction.createProperty(LP.polyVal, PropertyType.DOUBLE);

			VertexType general_session = txGraph.createVertexType(DBCN.V.general.session.cn, DBCN.V.cn);
			general_session.createProperty(LP.nodeUID, PropertyType.STRING);
			general_session.createProperty(LP.expState, PropertyType.INTEGER);
			general_session.createProperty(LP.lastPrecisionRate, PropertyType.DOUBLE);
			general_session.createProperty(LP.bestPrecisionRate, PropertyType.DOUBLE);
			general_session.createProperty(LP.bestPrecisionRateRid, PropertyType.STRING);
			//No should be added as the things that can be added in is large and we are responsible to calculate which is the best at the run time.
			general_session.createProperty(LP.banList, PropertyType.BINARY);
			general_session.createProperty(LP.greenList, PropertyType.BINARY);

			txGraph.createVertexType(DBCN.V.temp.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.temp.ICLPatternFeedback.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.temp.ICLPatternFeedback.rawData.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.temp.ICLPatternFeedback.rawData.visual.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.temp.ICLPatternFeedback.rawData.audio.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.temp.ICLPatternFeedback.rawData.movement.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.extInterface.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.extInterface.hw.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.extInterface.hw.camera.cn, DBCN.V.cn);
			VertexType extInterface_hw_camera_cam1 = txGraph.createVertexType(DBCN.V.extInterface.hw.camera.cam1.cn, DBCN.V.cn);
			//source data url.
			extInterface_hw_camera_cam1.createProperty(LP.data, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.extInterface.hw.mic.cn, DBCN.V.cn);
			VertexType extInterface_hw_mic_mic1 = txGraph.createVertexType(DBCN.V.extInterface.hw.mic.mic1.cn, DBCN.V.cn);
			extInterface_hw_mic_mic1.createProperty(LP.data, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.extInterface.hw.controller.cn, DBCN.V.cn);
			VertexType extInterface_hw_controller_rpi = txGraph.createVertexType(DBCN.V.extInterface.hw.controller.rpi.cn, DBCN.V.cn);
			extInterface_hw_controller_rpi.createProperty(LP.data, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.extInterface.sw.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.actionScheduler.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.actionScheduler.config.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.actionScheduler.addAction.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.actionScheduler.allAction.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.WM.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.WM.timeline.cn, DBCN.V.cn);
			VertexType WM_timeline_addPrediction = txGraph.createVertexType(DBCN.V.WM.timeline.addPrediction.cn, DBCN.V.cn);
			WM_timeline_addPrediction.createProperty(LP.sessionRid, PropertyType.STRING);
			txGraph.createVertexType(DBCN.V.WM.timeline.addPhysicalOutput.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.WM.timeline.errorRouteRid.cn, DBCN.V.cn);

			VertexType taskDetail = txGraph.createVertexType(DBCN.V.taskDetail.cn, DBCN.V.cn);
			taskDetail.createProperty(LP.data, PropertyType.BINARY);

			/*
			 * For job center, data field is the work storage, nodeUid is the optional field provided during creation of worker node, used to force
			 * remove the subscription to these task as if the worker crashes, we have completely no idea where to recover its original work storage
			 * id (the data field), or simply just time consuming (trace back to the main manager node, then dig out the work storage id).
			 */
			txGraph.createVertexType(DBCN.V.jobCenter.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataDistCacl.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataDistCacl.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataDistCacl.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataDistCacl.completed.cn, DBCN.V.cn);
			VertexType jc_crawler_rawDataDistCacl_worker = txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataDistCacl.worker.cn, DBCN.V.cn);
			jc_crawler_rawDataDistCacl_worker.createProperty(LP.data, PropertyType.STRING);
			jc_crawler_rawDataDistCacl_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataICL.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataICL.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataICL.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataICL.completed.cn, DBCN.V.cn);
			VertexType jc_crawler_rawDataICL_worker = txGraph.createVertexType(DBCN.V.jobCenter.crawler.rawDataICL.worker.cn, DBCN.V.cn);
			jc_crawler_rawDataICL_worker.createProperty(LP.data, PropertyType.STRING);
			jc_crawler_rawDataICL_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.crawler.STISS.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.STISS.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.STISS.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.STISS.completed.cn, DBCN.V.cn);
			VertexType jc_crawler_STISS_worker = txGraph.createVertexType(DBCN.V.jobCenter.crawler.STISS.worker.cn, DBCN.V.cn);
			jc_crawler_STISS_worker.createProperty(LP.data, PropertyType.STRING);
			jc_crawler_STISS_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSG.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSG.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSG.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSG.completed.cn, DBCN.V.cn);
			VertexType jc_crawler_RSG_worker = txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSG.worker.cn, DBCN.V.cn);
			jc_crawler_RSG_worker.createProperty(LP.data, PropertyType.STRING);
			jc_crawler_RSG_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.crawler.SCCRS.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.SCCRS.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.SCCRS.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.SCCRS.completed.cn, DBCN.V.cn);
			VertexType jc_crawler_SCCRS_worker = txGraph.createVertexType(DBCN.V.jobCenter.crawler.SCCRS.worker.cn, DBCN.V.cn);
			jc_crawler_SCCRS_worker.createProperty(LP.data, PropertyType.STRING);
			jc_crawler_SCCRS_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.crawler.ACTGDR.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.ACTGDR.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.ACTGDR.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.ACTGDR.completed.cn, DBCN.V.cn);
			VertexType jc_crawler_ACTGDR_worker = txGraph.createVertexType(DBCN.V.jobCenter.crawler.ACTGDR.worker.cn, DBCN.V.cn);
			jc_crawler_ACTGDR_worker.createProperty(LP.data, PropertyType.STRING);
			jc_crawler_ACTGDR_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSGFSB.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSGFSB.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSGFSB.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSGFSB.completed.cn, DBCN.V.cn);
			VertexType jc_crawler_RSGFSB_worker = txGraph.createVertexType(DBCN.V.jobCenter.crawler.RSGFSB.worker.cn, DBCN.V.cn);
			jc_crawler_RSGFSB_worker.createProperty(LP.data, PropertyType.STRING);
			jc_crawler_RSGFSB_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RERAUP.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RERAUP.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RERAUP.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.crawler.RERAUP.completed.cn, DBCN.V.cn);
			VertexType jc_crawler_RERAUP_worker = txGraph.createVertexType(DBCN.V.jobCenter.crawler.RERAUP.worker.cn, DBCN.V.cn);
			jc_crawler_RERAUP_worker.createProperty(LP.data, PropertyType.STRING);
			jc_crawler_RERAUP_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.STM.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.globalDistUpdate.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.globalDistUpdate.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.globalDistUpdate.processing.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.globalDistUpdate.completed.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.globalDistUpdate.worker.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.completed.cn, DBCN.V.cn);
			VertexType jc_STM_GCAMain_worker = txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.worker.cn, DBCN.V.cn);
			jc_STM_GCAMain_worker.createProperty(LP.data, PropertyType.STRING);
			jc_STM_GCAMain_worker.createProperty(LP.nodeUID, PropertyType.STRING);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.previous.cn, DBCN.V.cn);

			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.rawData.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.rawData.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.rawData.completed.cn, DBCN.V.cn);
			VertexType jv_STM_GCAMain_rawData_worker = txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.rawData.worker.cn, DBCN.V.cn);
			jv_STM_GCAMain_rawData_worker.createProperty(LP.data, PropertyType.STRING);
			jv_STM_GCAMain_rawData_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.rawDataICL.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.rawDataICL.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.rawDataICL.completed.cn, DBCN.V.cn);
			VertexType jc_STM_GCAMain_rawDataICL_worker = txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.rawDataICL.worker.cn, DBCN.V.cn);
			jc_STM_GCAMain_rawDataICL_worker.createProperty(LP.data, PropertyType.STRING);
			jc_STM_GCAMain_rawDataICL_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.exp.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.exp.task.cn, DBCN.V.cn);
			txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.exp.completed.cn, DBCN.V.cn);
			VertexType jc_STM_GCAMain_exp_worker = txGraph.createVertexType(DBCN.V.jobCenter.STM.GCAMain.exp.worker.cn, DBCN.V.cn);
			jc_STM_GCAMain_exp_worker.createProperty(LP.data, PropertyType.STRING);
			jc_STM_GCAMain_exp_worker.createProperty(LP.nodeUID, PropertyType.STRING);

			VertexType devNull = txGraph.createVertexType(DBCN.V.devnull.cn, DBCN.V.cn);
			devNull.createProperty(LP.data, PropertyType.STRING);
			//Index vertex type are not created as they are not yet final.

			VertexType log = txGraph.createVertexType(DBCN.V.log.cn, DBCN.V.cn);
			log.createProperty(Logger.LPLOG.credentialUid, PropertyType.STRING);
			log.createProperty(Logger.LPLOG.LVL, PropertyType.STRING);
			log.createProperty(Logger.LPLOG.CLA, PropertyType.STRING);
			log.createProperty(Logger.LPLOG.message, PropertyType.STRING);
			log.createProperty(Logger.LPLOG.exception, PropertyType.STRING);
			log.createProperty(Logger.LPLOG.timeStamp, PropertyType.LONG);

			VertexType logCredential = txGraph.createVertexType(DBCN.V.logCredential.cn, DBCN.V.cn);
			logCredential.createProperty(Logger.LPCREDENTIAL.UID, PropertyType.STRING);
			logCredential.createProperty(Logger.LPCREDENTIAL.threadName, PropertyType.STRING);
			logCredential.createProperty(Logger.LPCREDENTIAL.parentName, PropertyType.STRING);
			logCredential.createProperty(Logger.LPCREDENTIAL.taskList, PropertyType.STRING);
			logCredential.createProperty(Logger.LPCREDENTIAL.timeStamp, PropertyType.LONG);

			//Console feedback data is the log message generated into 1 string and committed to him.
			VertexType consoleFeedback = txGraph.createVertexType(DBCN.V.consoleFeedback.cn, DBCN.V.cn);
			consoleFeedback.createProperty(LP.data, PropertyType.STRING);

			//The configuration vertexes to manage clustered index.
			VertexType index_WMTimeRanIndex = txGraph.createVertexType(DBCN.index.WMTimeRanIndex.cn, DBCN.V.cn);
			index_WMTimeRanIndex.createProperty(LP.initialized, PropertyType.BOOLEAN);
			index_WMTimeRanIndex.createProperty(LP.maxClusterCount, PropertyType.INTEGER);
			index_WMTimeRanIndex.createProperty(LP.elementPerCluster, PropertyType.INTEGER);
			index_WMTimeRanIndex.createProperty(LP.currentInsertClusterIndex, PropertyType.INTEGER);
			index_WMTimeRanIndex.createProperty(LP.currentInsertClusterElementCount, PropertyType.INTEGER);
			VertexType index_WMPrecisionRateIndex = txGraph.createVertexType(DBCN.index.WMPrecisionRateIndex.cn, DBCN.V.cn);
			index_WMPrecisionRateIndex.createProperty(LP.initialized, PropertyType.BOOLEAN);
			index_WMPrecisionRateIndex.createProperty(LP.maxClusterCount, PropertyType.INTEGER);
			index_WMPrecisionRateIndex.createProperty(LP.elementPerCluster, PropertyType.INTEGER);
			index_WMPrecisionRateIndex.createProperty(LP.currentInsertClusterIndex, PropertyType.INTEGER);
			index_WMPrecisionRateIndex.createProperty(LP.currentInsertClusterElementCount, PropertyType.INTEGER);

			for (int i=0; i<workStorageCount; i++) {
				NumberFormat formatter = new DecimalFormat("00000");
				txGraph.createVertexType("W" + formatter.format(i), DBCN.V.cn);
			}

			for (int i=0; i<workStorageCount; i++) {
				NumberFormat formatter = new DecimalFormat("00000");
				Vertex add = txGraph.addVertex(DBCN.V.worker.storageRegister.cn, DBCN.V.worker.storageRegister.cn);
				add.setProperty(LP.storageId, "W" + formatter.format(i));
				add.setProperty(LP.isRegistered, false);
				add.setProperty(LP.registrant, "");
				add.setProperty(LP.lastPingTime, System.currentTimeMillis());
				add.setProperty(LP.pingLatency, 0);
			}

			//Setup the initial vertexes.
			Vertex V_extInterface_hw_camera_cam1 = txGraph.addVertex(DBCN.V.extInterface.hw.camera.cam1.cn, DBCN.V.extInterface.hw.camera.cam1.cn);
			V_extInterface_hw_camera_cam1.setProperty(LP.data, "http://10.42.0.92:8080/shot.jpg");

			Vertex V_extInterface_hw_mic_mic1 = txGraph.addVertex(DBCN.V.extInterface.hw.mic.mic1.cn, DBCN.V.extInterface.hw.mic.mic1.cn);
			V_extInterface_hw_mic_mic1.setProperty(LP.data, "http://10.42.0.92:8080/audio.wav");

			Vertex V_extInterface_hw_controller_rpi = txGraph.addVertex(DBCN.V.extInterface.hw.controller.rpi.cn, DBCN.V.extInterface.hw.controller.rpi.cn);
			V_extInterface_hw_controller_rpi.setProperty(LP.data, "10.42.0.56");

			Vertex V_worker_registrar = txGraph.addVertex(DBCN.V.worker.registrar.cn, DBCN.V.worker.registrar.cn);
			V_worker_registrar.setProperty(LP.hostName, "UNSET");
			V_worker_registrar.setProperty(LP.port, 4567);

			Vertex WMRequestListener = txGraph.addVertex(DBCN.V.worker.WMRequestListener.cn, DBCN.V.worker.WMRequestListener.cn);
			WMRequestListener.setProperty(LP.nodeUID, "UNSET");
			WMRequestListener.setProperty(LP.hostName, "UNSET");
			WMRequestListener.setProperty(LP.port, 9988);

			Vertex V_globalDist_out = txGraph.addVertex(V.globalDist.out.cn, V.globalDist.out.cn);
			V_globalDist_out.setProperty(LP.data, 50d);

			Vertex V_jc_STM_GCAmain_previous = txGraph.addVertex(V.jobCenter.STM.GCAMain.previous.cn, V.jobCenter.STM.GCAMain.previous.cn);
			Vertex V_general_GCAMain = txGraph.addVertex(V.general.GCAMain.cn, V.general.GCAMain.cn);
			V_general_GCAMain.setProperty(LP.timeStamp, System.currentTimeMillis());
			V_jc_STM_GCAmain_previous.addEdge(DBCN.E.source, V_general_GCAMain);

			Vertex V_index_WMTimeRanIndex = txGraph.addVertex(DBCN.index.WMTimeRanIndex.cn, DBCN.index.WMTimeRanIndex.cn);
			V_index_WMTimeRanIndex.setProperty(LP.initialized, false);
			Vertex V_index_WMPrecisionRateIndex = txGraph.addVertex(DBCN.index.WMPrecisionRateIndex.cn, DBCN.index.WMPrecisionRateIndex.cn);
			V_index_WMPrecisionRateIndex.setProperty(LP.initialized, false);
