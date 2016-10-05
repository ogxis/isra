package stm;

/**
 * Actual data's general vertex, ranging from raw data to internal data. defines all the general LTM into a array for easier access and encapsulation.
 * Here we generally means things that are classified under LTM category, not necessary the LTM data vertex itself.
 */
public class LTM {
	/*
	 * NOTE that LTM's LTM is real LTM, and its holds an edge to another general vertex of its own, we are referring to that general vertex here,
	 * not the actual LTM vertex that stores the real data, but the LTM's general that store external edges for the actual LTM that stores data.
	 * We here exempt GCA and exp, as both of them has their own unique way of operation during those internal generation process.
	 * We here only take care of those data which are not GCA and exp with a general interface.
	 *
	 * These 'general' must have a corresponding LTM entry to be qualified into this list, which is by design, all of them.
	 */
	public static final String[] GENERAL = {
			DBCN.V.general.rawData.PI.dev.camera1.cn,
			DBCN.V.general.rawData.PI.dev.mic1.cn,
			//Convergence is controversial, it is similar to exp requirement and result.
			DBCN.V.general.convergenceMain.cn,
			DBCN.V.general.convergenceSecondary.cn,
			DBCN.V.general.rawData.POFeedback.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor1.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor2.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor3.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor4.cn,
			DBCN.V.general.rawData.POFeedback.dev.speaker1.cn,
			DBCN.V.general.rawDataICL.cn,
			DBCN.V.general.rawDataICL.visual.cn,
			DBCN.V.general.rawDataICL.audio.cn,
			DBCN.V.general.rawDataICL.movement.cn
	};
	//Can be exp of any type and raw data, raw data ICL (patterns) of any type.
	public static final String[] DEMAND_DATA = {
			DBCN.V.general.rawData.PI.dev.camera1.cn,
			DBCN.V.general.rawData.PI.dev.mic1.cn,
			DBCN.V.general.rawData.POFeedback.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor1.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor2.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor3.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor4.cn,
			DBCN.V.general.rawData.POFeedback.dev.speaker1.cn,
			DBCN.V.general.rawDataICL.cn,
			DBCN.V.general.rawDataICL.visual.cn,
			DBCN.V.general.rawDataICL.audio.cn,
			DBCN.V.general.rawDataICL.movement.cn,
			DBCN.V.general.exp.cn,
			DBCN.V.general.exp.requirement.cn,
			DBCN.V.general.exp.result.cn,
			DBCN.V.general.exp.prediction.cn,
			DBCN.V.LTM.exp.cn,
			DBCN.V.LTM.exp.requirement.cn,
			DBCN.V.LTM.exp.result.cn,
			DBCN.V.LTM.exp.prediction.cn
	};
	public static final String[] EXP = {
			DBCN.V.general.exp.cn,
			DBCN.V.general.exp.requirement.cn,
			DBCN.V.general.exp.result.cn,
			DBCN.V.general.exp.prediction.cn,
			DBCN.V.LTM.exp.cn,
			DBCN.V.LTM.exp.requirement.cn,
			DBCN.V.LTM.exp.result.cn,
			DBCN.V.LTM.exp.prediction.cn
	};

	public static final String[] DATA = {
			DBCN.V.LTM.rawData.PI.dev.camera1.cn,
			DBCN.V.LTM.rawData.PI.dev.mic1.cn,
			//Convergence is controversial, it is similar to exp requirement and result. Here in LTM data column we have no convergence.
			DBCN.V.LTM.rawData.POFeedback.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.motor1.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.motor2.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.motor3.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.motor4.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.speaker1.cn,
			DBCN.V.LTM.rawDataICL.cn,
			DBCN.V.LTM.rawDataICL.visual.cn,
			DBCN.V.LTM.rawDataICL.audio.cn,
			DBCN.V.LTM.rawDataICL.movement.cn
	};

	public static final String[] ICL = {
			DBCN.V.general.rawDataICL.cn,
			DBCN.V.general.rawDataICL.visual.cn,
			DBCN.V.general.rawDataICL.audio.cn,
			DBCN.V.general.rawDataICL.movement.cn,
			DBCN.V.LTM.rawDataICL.cn,
			DBCN.V.LTM.rawDataICL.visual.cn,
			DBCN.V.LTM.rawDataICL.audio.cn,
			DBCN.V.LTM.rawDataICL.movement.cn
	};

	//Without specific class (_) means it contains all of them.
	public static final String[] VISUAL = {
			DBCN.V.general.rawData.PI.dev.camera1.cn,
			DBCN.V.general.rawDataICL.visual.cn
	};
	public static final String[] VISUAL_RAW = {
			DBCN.V.general.rawData.PI.dev.camera1.cn,
			DBCN.V.LTM.rawData.PI.dev.camera1.cn
	};
	public static final String[] VISUAL_ICL = {
			DBCN.V.general.rawDataICL.visual.cn,
			DBCN.V.LTM.rawDataICL.visual.cn
	};

	public static final String[] AUDIO = {
			DBCN.V.general.rawData.PI.dev.mic1.cn,
			DBCN.V.general.rawData.POFeedback.dev.speaker1.cn,
			DBCN.V.general.rawDataICL.audio.cn
	};
	public static final String[] AUDIO_RAW = {
			DBCN.V.general.rawData.PI.dev.mic1.cn,
			DBCN.V.general.rawData.POFeedback.dev.speaker1.cn,
			DBCN.V.LTM.rawData.PI.dev.mic1.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.speaker1.cn,
	};
	public static final String[] AUDIO_ICL = {
			DBCN.V.general.rawDataICL.audio.cn,
			DBCN.V.LTM.rawDataICL.audio.cn,
	};

	public static final String[] MOVEMENT = {
			DBCN.V.general.rawDataICL.movement.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor1.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor2.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor3.cn,
			DBCN.V.general.rawData.POFeedback.dev.motor4.cn,
			DBCN.V.LTM.rawDataICL.movement.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.motor1.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.motor2.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.motor3.cn,
			DBCN.V.LTM.rawData.POFeedback.dev.motor4.cn,
	};
}
