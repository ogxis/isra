package ICL;

import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Queue;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;

import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import stm.LTM;
import utilities.Util;

/**
 * Identification and Classification Loop (ICL) are series of functions that are used by internal system to identify and classify data.
 */
public abstract class ICL {
	/**
	 * Setup the audio header. Used during audio cropping, as we store only the audio data without its header, when fetched from
	 * DB before use we must stitch back the header for it to work properly.
	 * TODO: May be volatile in the future, but we now treat it as static.
	 */
	public static void init() {
		//Setup the audio header, using 100ms per input latency.
		//Select the sample length to extract header from. Tenth means 100ms, thus 4410 data (4410 * 10 = 44100hz, 100ms * 10 = 1s)
		Path path = Paths.get("resources/audioICL/audioHeader/tenthSecSample.wav");
		try {
			byte[] data = Files.readAllBytes(path);
			ICL.Audio.setAudioDataHeader(data);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * General ICL implementation that works on all internal link, anything thing other than Physical IO can use these functions.
	 */
	public static class General {
		/**
		 * For use with function deduceType, to serve as its return type as java doesn't support pass-by-ref. Store deduced data after deduction in their
		 * original data form.
		 * NOTE: DeducedType, deduceHybridType, DeducedTypeRid, getOccurrenceOfDeducedTypeInFormOfRid, deducedTypeToRid and
		 * deducedTypeRelevancyCalculationByExistence (6 of them) must change together if any one of them changes as they all uses the same class and idea.
		 * TODO: change arraylist to tree for faster comparison. no implemented now because of comparator.
		 */
		private static class DeducedType {
			ArrayList<Vertex> visual;
			ArrayList<Vertex> audio;
			ArrayList<Vertex> movement;
			ArrayList<Vertex> polyVal;
			ArrayList<Vertex> convergence;
			ArrayList<Vertex> exp;
		}

		/**
		 * Deduce complex composite type back into smaller, known type for every vertex.
		 * @param data The data vertex to be deduced.
		 * @return A DeducedType object storing all the elements of classified type.
		 * NOTE: DeducedType, deduceHybridType, DeducedTypeRid, expandDeducedTypeToOccurrenceScaleThenConvertToRidForm, deducedTypeToRid and
		 * deducedTypeRelevancyCalculationByExistence (6 of them) must change together if any one of them changes as they all uses the same class and idea.
		 */
		private static DeducedType deduceHybridType(Vertex data) {
			//Differentiate each data into different nodes.
			//raw pattern means ICL result, gca mean primitive timeline combination, exp means experience.
			DeducedType result = new DeducedType();

			//TODO: THIS ORIGINALLY EXPECT ACTUAL LTM data VERTEX, and you didnt provide it after you made update to provide general vertex only.
			String dataType = data.getCName();
			//TODO: Same with the types above. If they changes this should change too.
			if (Util.equalAny(dataType, LTM.VISUAL)) {
				result.visual.add(data);
			}
			else if (Util.equalAny(dataType, LTM.AUDIO)) {
				result.audio.add(data);
			}
			else if (Util.equalAny(dataType, LTM.MOVEMENT)) {
				result.movement.add(data);
			}
			else if (dataType.equals(DBCN.V.LTM.polyVal.rawData.visual.cn) || dataType.equals(DBCN.V.LTM.polyVal.rawData.audio.cn) ||
					dataType.equals(DBCN.V.LTM.polyVal.rawData.movement.cn)) {
				result.polyVal.add(data);
			}
			else if (dataType.equals(DBCN.V.general.convergenceMain.cn) || dataType.equals(DBCN.V.general.convergenceSecondary.cn) ) {
				result.convergence.add(data);
			}
			//TODO: experience is WM, when WM is ready, make this identify WM experience data.
			else if (dataType.equals(DBCN.V.general.exp.cn) || dataType.equals(DBCN.V.general.exp.requirement.cn) ||
					dataType.equals(DBCN.V.general.exp.result.cn) || dataType.equals(DBCN.V.LTM.exp.cn) ||
					dataType.equals(DBCN.V.LTM.exp.requirement.cn) || dataType.equals(DBCN.V.LTM.exp.result.cn) ) {
				result.exp.add(data);
			}
			else
				throw new IllegalStateException("Unknown/Unsupported dataType for GCA vertex:"
						+ data.getRid() + "; Unknown type:" + dataType);

			return result;
		}

		/**
		 * Store the rids of every occurrence data from DeducedType, all occurrence of every element of those data in composite form(does not care who and
		 * whom we get those rids from, just care about categorizing their types).
		 * NOTE: DeducedType, deduceHybridType, DeducedTypeRid, expandDeducedTypeToOccurrenceScaleThenConvertToRidForm, deducedTypeToRid and
		 * deducedTypeRelevancyCalculationByExistence (6 of them) must change together if any one of them changes as they all uses the same class and idea.
		 * TODO: change arraylist to tree for faster comparison. no implemented now because of comparator.
		 */
		private static class DeducedTypeRid {
			ArrayList<String> visual;
			ArrayList<String> audio;
			ArrayList<String> movement;
			ArrayList<String> polyVal;
			ArrayList<String> convergence;
			ArrayList<String> exp;
		}

		/**
		 * After 'deduceHybridType', run this to generate a view combining all occurrence vertexes' RID from every single vertex of 'deduceHybridType' result.
		 * @param dataArray The result of 'deduceHybridType', a list of vertexes categorized by type.
		 * @return All occurrence vertex of the result of 'deduceHybridType' in form of RIDs.
		 * NOTE: DeducedType, deduceHybridType, DeducedTypeRid, expandDeducedTypeToOccurrenceScaleThenConvertToRidForm, deducedTypeToRid and
		 * deducedTypeRelevancyCalculationByExistence (6 of them) must change together if any one of them changes as they all uses the same class and idea.
		 *
		 */
		private static DeducedTypeRid expandDeducedTypeToOccurrenceScaleThenConvertToRidForm (DeducedType dataArray) {
			//TODO: optimize it by fetching only the rids, without all the data, we don't need those data.
			//To get occurrence, we need to traverse to their parent, then get all related siblings.
			DeducedTypeRid result = new DeducedTypeRid();
			for (Vertex data : dataArray.visual) {
				ArrayList<Vertex> occurrenceList = Util.traverseGetOccurrence(data);
				for (Vertex occurrence : occurrenceList)
					result.visual.add(occurrence.getRid());
			}
			for (Vertex data : dataArray.audio) {
				ArrayList<Vertex> occurrenceList = Util.traverseGetOccurrence(data);
				for (Vertex occurrence : occurrenceList)
					result.audio.add(occurrence.getRid());
			}
			for (Vertex data : dataArray.movement) {
				ArrayList<Vertex> occurrenceList = Util.traverseGetOccurrence(data);
				for (Vertex occurrence : occurrenceList)
					result.movement.add(occurrence.getRid());
			}
			for (Vertex data : dataArray.polyVal) {
				ArrayList<Vertex> occurrenceList = Util.traverseGetOccurrence(data);
				for (Vertex occurrence : occurrenceList)
					result.polyVal.add(occurrence.getRid());
			}
			for (Vertex data : dataArray.convergence) {
				ArrayList<Vertex> occurrenceList = Util.traverseGetOccurrence(data);
				for (Vertex occurrence : occurrenceList)
					result.convergence.add(occurrence.getRid());
			}
			for (Vertex data : dataArray.exp) {
				ArrayList<Vertex> occurrenceList = Util.traverseGetOccurrence(data);
				for (Vertex occurrence : occurrenceList)
					result.exp.add(occurrence.getRid());
			}
			return result;
		}

		/**
		 * Convert all data in deduced type into RIDs, while retaining the same storage architecture.
		 * @param dataArray The 'DeducedType' object containing all the vertexes that you want their RIDs from.
		 * @return A 'DeducedTypeRid' containing all the RIDs converted.
		 * NOTE: DeducedType, deduceHybridType, DeducedTypeRid, expandDeducedTypeToOccurrenceScaleThenConvertToRidForm, deducedTypeToRid and
		 * deducedTypeRelevancyCalculationByExistence (6 of them) must change together if any one of them changes as they all uses the same class and idea.
		 */
		private static DeducedTypeRid deducedTypeToRid (DeducedType dataArray) {
			//TODO: optimize it by fetching only the rids, without all the data, we don't need those data.
			DeducedTypeRid result = new DeducedTypeRid();
			for (int i=0; i<dataArray.visual.size(); i++)
				result.visual.add( dataArray.visual.get(i).getRid() );
			for (int i=0; i<dataArray.audio.size(); i++)
				result.audio.add( dataArray.audio.get(i).getRid() );
			for (int i=0; i<dataArray.movement.size(); i++)
				result.movement.add( dataArray.movement.get(i).getRid() );
			for (int i=0; i<dataArray.polyVal.size(); i++)
				result.polyVal.add( dataArray.polyVal.get(i).getRid() );
			for (int i=0; i<dataArray.convergence.size(); i++)
				result.convergence.add( dataArray.convergence.get(i).getRid() );
			for (int i=0; i<dataArray.exp.size(); i++)
				result.exp.add( dataArray.exp.get(i).getRid() );
			return result;
		}

		/**
		 * Calculate relevancy between 2 trees of RIDs by their existence(relativity). If occurrence RIDs is available in demand's siblings (occurrences)
		 * RIDs, means they share the same siblings, thus it will be counted as match.
		 * @param demandTree Result of 'expandDeducedTypeToOccurrenceScaleThenConvertToRidForm' from original demand from its origin function.
		 * @param occurrenceTree Result of 'deducedTypeToRid' from original occurrence from its origin function.
		 * @return Their relevancy in terms of polyVal.
		 * NOTE: DeducedType, deduceHybridType, DeducedTypeRid, expandDeducedTypeToOccurrenceScaleThenConvertToRidForm, deducedTypeToRid and
		 * deducedTypeRelevancyCalculationByExistence (6 of them) must change together if any one of them changes as they all uses the same class and idea.
		 */
		private static double deducedTypeRelevancyCalculationByExistence (DeducedTypeRid demandTree, DeducedTypeRid occurrenceTree) {
			//Calculate the total amount of RIDs available in occurrenceTree. We match against him, thus he will be the standard.
			//demandTree cannot be the standard as he has already combined all of its occurrence within 1 range traversal, he will be the dataset.
			int maxMatchCount = occurrenceTree.visual.size() + occurrenceTree.audio.size() + occurrenceTree.movement.size()
								+ occurrenceTree.polyVal.size() + occurrenceTree.convergence.size() + occurrenceTree.exp.size();
			int matchCount = 0;
			for (String target : occurrenceTree.visual) {
				for (String data : demandTree.visual) {
					if (target.equals(data)) {
						matchCount++;
						break;
					}
				}
			}
			for (String target : occurrenceTree.audio) {
				for (String data : demandTree.audio) {
					if (target.equals(data)) {
						matchCount++;
						break;
					}
				}
			}
			for (String target : occurrenceTree.movement) {
				for (String data : demandTree.movement) {
					if (target.equals(data)) {
						matchCount++;
						break;
					}
				}
			}
			for (String target : occurrenceTree.polyVal) {
				for (String data : demandTree.polyVal) {
					if (target.equals(data)) {
						matchCount++;
						break;
					}
				}
			}
			for (String target : occurrenceTree.convergence) {
				for (String data : demandTree.convergence) {
					if (target.equals(data)) {
						matchCount++;
						break;
					}
				}
			}
			for (String target : occurrenceTree.exp) {
				for (String data : demandTree.exp) {
					if (target.equals(data)) {
						matchCount++;
						break;
					}
				}
			}
			assert matchCount < maxMatchCount : "matchCount:" + matchCount + ", is larget than maxMatchCount:" + matchCount;
			return (double)matchCount / (double)maxMatchCount * 100d;
		}

		/**
		 * Compare all types of occurrence' result and return how similar a vertex is regarding to its formation without going in depth recursive pairing.
		 * NOTE: BOTH be 'Data''s general vertex. Means a 'general' data vertex that have link to another real 'data' vertex.
		 * @param demandResult The demand that user are expecting to achieve.
		 * @param occurrenceResult The result(occurrence/siblings) we extracted from experience, a list of them.
		 * @return How similar both of these results are represented by polyVal, in ordered form based on original 'occurrenceResultList' ordering.
		 */
		public static ArrayList<Double> compareOccurrenceResult (Vertex demandResult, ArrayList<Vertex> occurrenceResultList) {
			//Deduce them into multiple basic category specified in class 'DeducedType'.
			DeducedType demandDeduced = deduceHybridType(demandResult);
			//Traverse those deduced data to get all of their occurrences(siblings), then convert those occurrences into RIDs.
			DeducedTypeRid demandDeducedRidTree = expandDeducedTypeToOccurrenceScaleThenConvertToRidForm(demandDeduced);

			ArrayList<Double> result = new ArrayList<Double>(occurrenceResultList.size());
			for (Vertex occurrenceResult : occurrenceResultList) {
				//Deduce the given 'occurrenceResult''s data into multiple categories, then convert those data directly into RIDs WITHOUT getting
				//their occurrence(siblings) like how 'demandExpDeduced' did.
				DeducedType occurrenceDeduced = deduceHybridType(occurrenceResult);
				DeducedTypeRid occurrenceDeducedRidTree = deducedTypeToRid(occurrenceDeduced);

				//Compare both of them to see if they are related. demand dataset are larger as we need to get all of their siblings for fair comparison,
				//as it is nearly impossible for occurrenceResult to have such coincident to match directly with demand, but it has high chances of
				//matching with its siblings. Anyway to be siblings, they should be almost identical too, so we treat it as matches.
				result.add( deducedTypeRelevancyCalculationByExistence(demandDeducedRidTree, occurrenceDeducedRidTree) );
			}
			return result;
		}

//		/**
//		 * NOTE: TEMPORARY UNUSED, we don't do any in depth calculation of relevancy, we only do existence calculation.
//		 * Compare all types of occurrence' result and return how similar a vertex is regarding to its formation without going in depth recursive pairing.
//		 * NOTE: BOTH be 'Data''s general vertex. Means a 'general' data vertex that have link to another real 'data' vertex.
//		 * @param demandResult The demand that user are expecting to achieve.
//		 * @param occurrenceResult The result we extracted from experience.
//		 * @return How similar both of these results are represented by polyVal.
//		 */
//		public static double compareOccurrenceResult(Vertex demandResult, Vertex occurrenceResult, double globalVariance) {
//			String demandDataType = ((OrientVertex)demandResult).getCName();
//			String occurrenceDataType = ((OrientVertex)occurrenceResult).getCName();
//
//			//Result may contain raw data or experiences or hybrid of these.
//			//Get all the data laid flat out here, then begin comparison.
//			//Get its basic type first.
//			//NOTE: Data are expected to be grouped into these groups: raw data patterns OR combination of data OR experience.
//			//Raw data itself will never appear here as he should be computed at the first ICL phrase.
//			if (demandDataType.equals(occurrenceDataType)) {
//				boolean typeMismatch = false;
//				if (demandDataType.equals(STMDefine.className.general.DBCN.visual)) {
//					//A simple direct comparison with epsilon enabled.
//					if (Visual.checkVisualBaseType(demandResult, occurrenceResult))
//						return Visual.getRelevancyVisualData(demandResult, occurrenceResult, globalVariance);
//					typeMismatch = true;
//				}
//
//				else if (demandDataType.equals(STMDefine.className.general.DBCN.audio)) {
//					if (Audio.checkAudioBaseType(demandResult, occurrenceResult))
//						return Audio.getRelevancyAudioData(demandResult, occurrenceResult, globalVariance);
//					typeMismatch = true;
//				}
//
//				else if (demandDataType.equals(STMDefine.className.general.DBCN.movement)) {
//					throw new IllegalStateException("NOT YET IMPLEMENTED!");
//				}
//
//				//For relevancy between 2 polyVal, we just take their average.
//				else if (demandDataType.equals(STMDefine.className.LTM.polyVal.DBCN.visual) ||
//						demandDataType.equals(STMDefine.className.LTM.polyVal.DBCN.audio) ||
//						demandDataType.equals(STMDefine.className.LTM.polyVal.DBCN.movement)) {
//					double demand = Util.traverseGetDataField(demandResult, Direction.IN, LP.data);
//					double result = Util.traverseGetDataField(occurrenceResult, Direction.IN, LP.data);
//					return (demand+result)/2;
//				}
//
//				//This general existence calculation should be removed.
////				//Hybrid type. Here we need to deduce individual element to pair.
////				//They uses another type of hierarchy here, called timed events/operation. Experience model minimal version.
////				else if (demandDataType.equals(STMDefine.className.general.experienceData.requirement) ||
////					 	 demandDataType.equals(STMDefine.className.general.experienceData.result)) {
////					String expType;
////					if (demandDataType.equals(STMDefine.className.general.experienceData.requirement))
////						expType = LP.experience.requirement;
////					else
////						expType = LP.experience.result;
////					//Differentiate each data into different nodes.
////					//Get from general occurrence vertex to specific exp data vertex, then from that data vertex get all its data that are stored in other
////					//raw data vertexes or links to other exp vertexes.
////					ArrayList<Vertex> demandExp = Util.traverse( (Util.traverse(demandResult, Direction.IN, expType).get(0)), Direction.OUT, LP.data);
////
////					//Deduce them into multiple basic category specified in class 'DeducedType'
////					DeducedType demandExpDeduced = deduceHybridType(Util.traverse(demandResult, Direction.IN, expType));
////					//Traverse those deduced data to get all of their occurrences(siblings), then convert those occurrences into RIDs.
////					DeducedTypeRid demandExpDeducedRidTree = expandDeducedTypeToOccurrenceScaleThenConvertToRidForm(demandExpDeduced);
////
////					//Deduce the given 'occurrenceResult''s data into multiple categories, then convert those data directly into RIDs WITHOUT getting
////					//their occurrence(siblings) like how 'demandExpDeduced' did.
////					DeducedType occurrenceDeduced = deduceHybridType(Util.traverse(occurrenceResult, Direction.IN, expType));
////					DeducedTypeRid occurrenceDeducedRidTree = deducedTypeToRid(occurrenceDeduced);
////
////					//Compare both of them to see if they are related. demand dataset are larger as we need to get all of their siblings for fair comparison,
////					//as it is nearly impossible for occurrenceResult to have such coincident to match directly with demand, but it has high chances of
////					//matching with its siblings. Anyway to be siblings, they should be almost identical too, so we treat it as matches.
////					return deducedTypeRelevancyCalculationByExistence(demandExpDeducedRidTree, occurrenceDeducedRidTree);
////				}
////
//				//Minimal version only support requirement and result, operation are not being deduced.
//				else if (demandDataType.equals(STMDefine.className.general.DBCN.process)) {
//					throw new IllegalStateException("NOT YET IMPLEMENTED!");
//				}
//
//				else
//					throw new IllegalStateException("Data type not supported. DataType:" + demandDataType);
//
//				if (typeMismatch) {
//					throw new IllegalStateException("Visual data format not matching. demandVertex:" + ((OrientVertex)demandResult).getCName()
//							+ "; experienceResult:" + ((OrientVertex)occurrenceResult).getCName());
//				}
//			}
//
//			//Type must matches, if they don't, it means the DB entries doesn't follow convention, thus resulting issue here.
//			//Like class name in DB changes but forgot to update its references here OR someone manually add new entries.
//			throw new IllegalStateException ("Unrecognized data type, either one or both of these have defects. demandDataType':" + demandDataType
//					+ "; occurrenceDataType':" + occurrenceDataType + "; Most likely somebody manually configured DB but forgot to update ref.");
//		}
	}

	/**
	 * ICL functions related to raw visual data only.
	 */
	public static class Visual {
		/**
		 * Fetch binary image from specified vertex and convert it to openCV Mat format.
		 * @param targetVertex General Visual Vertex, the vertex that contain an edge to external data vertex which contain binary image data.
		 * @return openCV Mat.
		 */
		public static Mat visualVertexToMat (Vertex targetVertex) {
			//get and save img data from/to DB
			//http://stackoverflow.com/questions/25068645/classcastexception-when-trying-to-get-orecordbytes-with-orientdb-via-graph-api
			Vertex dataVertex = Util.traverseOnce(targetVertex, Direction.IN, DBCN.E.data, LTM.VISUAL_RAW);

			byte[] imageData = dataVertex.getProperty(LP.data);
			//convert byte[] to Mat
			//http://stackoverflow.com/questions/21113190/how-to-get-the-mat-object-from-the-byte-in-opencv-android
			Mat frame = Imgcodecs.imdecode(new MatOfByte(imageData), Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
			return frame;
		}

		public static Mat byteArrayToMat (byte[] byteArray) {
			return Imgcodecs.imdecode(new MatOfByte(byteArray), Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
		}

		/**
		 * Scan the whole image to get visual related data distribution. The normalized value.
		 * @param targetVertex The general vertex that have link to the actual image, not the actual data vertex itself.
		 * The vertex that contain raw visual data which had not been processed before.
		 * @return The distribution value in terms of 0~100 percent.
		 */
		public static double scanVisualDistribution (Vertex targetVertex) {
			Mat frame = visualVertexToMat(targetVertex);

			//Opencv native mean code, add up scalar 3 value bgr, forth value is not added as it is alpha, if the channel doesn't
			//exist, it returns 0, therefore it is fine even if it is grayscale. Divide by 3 to average it, then divide 255 (upper limit)
			//to convert it to 0~1 scale, then * 100 to make it into percentage 0~100 scale.
			Scalar mean = Core.mean(frame);
			double distribution = (mean.val[0] + mean.val[1] + mean.val[2]) / 3d / 255d * 100d;

			return distribution;
		}

		/**
		 * Access individual pixel data, one point only.
		 * @param targetVertex The general vertex that have link to the actual image, not the actual data vertex itself.
		 * @param coordinate Java Point class, fill in with x and y data.
		 * @return A double[] which contains the BGR value of the pixel.
		 */
		//TODO: make it accept varags and return ArrayList of double[].
		public static double[] getIndividualVisualData (Vertex targetVertex, Point coordinate) {
			Mat frame = visualVertexToMat(targetVertex);

			return frame.get(coordinate.x, coordinate.y);
		}

		/**
		 * Access individual pixel data, unlimited number of points.
		 * @param targetVertex The general vertex that have link to the actual image, not the actual data vertex itself.
		 * @param coordinate Java Point class, fill in with x and y data.
		 * @return A double[] which contains the BGR value of the pixel, immediately followed by next coordinate BGR in series until end of data.
		 */
		public static ArrayList<Double> getIndividualVisualData (Vertex targetVertex, ArrayList<Point> coordinate) {
			Mat frame = visualVertexToMat(targetVertex);

			ArrayList<Double> result = new ArrayList<Double>(coordinate.size()*frame.channels());
			for (int i=0; i<coordinate.size(); i++) {
				double[] pt = frame.get(coordinate.get(i).x, coordinate.get(i).y);
				for (double d : pt) {
					result.add(d);
				}
			}
			return result;
		}

		/**
		 * Calculate how relevant visual data are within v1 and v2 's data.
		 * Note: The data must be in same type, we don't do type checking here, proper conversion should had been done beforehand.
		 * @param v1,v2 IMPORTANT: Actual data vertex, not general vertex.
		 * @param thresholdInPolyVal Allowed offset percentage that will still be considered acceptable.
		 * @return A value between 0~100 to indicate how relevant they are. 100 is exact match, 0 is no matching point at all.
		 */
		public static double getRelevancyVisualData(Vertex v1, Vertex v2, double thresholdInPolyVal) {
			//convert byte[] to Mat
			Mat v1Mat = visualVertexToMat(v1);
			Mat v2Mat = visualVertexToMat(v2);

			//the total count of pixels with channel as its padding
			int arraySize = (int) (v1Mat.total() * v1Mat.channels());
			byte v1Arr[] = new byte[arraySize];
			byte v2Arr[] = new byte[arraySize];

			//fetch the whole image at once and translate it into linear data format, separated into RGB, 3 index per pixel form.
			v1Mat.get(0, 0, v1Arr);
			v2Mat.get(0, 0, v2Arr);

			//convert polyVal threshold specified in percent back to actual scale of visual data (0~255 uchar)
			int convertedThreshold = (int) Util.polyValDenormalize(0, 255, thresholdInPolyVal);
			int matchingData = 0;
			for (int i=0; i<arraySize; i++) {
				//if their offset are within threshold zone.
				//http://stackoverflow.com/questions/4266756/can-we-make-unsigned-byte-in-java
				//NOTE: In java byte is signed thus -128~127, but we want 0~255, this will convert them to unsigned 0~255.
				int data1 = v1Arr[i] & 0xFF;
				int data2 = v2Arr[i] & 0xFF;

				if (Math.abs(data1 - data2) <= convertedThreshold)
					matchingData++;
			}

			return ((double)matchingData / (double) arraySize) * 100.0d;
		}
	}
	/**
	 * ICL for raw audio data only. Will translate all data into format that general ICL can recognize.
	 * Operate on byte directly instead of float, uses signed byte.
	 */
	public static class Audio {
		/**
		 * Remove first 46 byte then we are left with the raw data for wav file type.
		 * 0~45 = 46 byte, so index is 46 (inclusive), which basically means we will start from byte 47 (index 46).
		 * @param audioDataWithHeader
		 * @return Audio data without header, pure audio stream data only.
		 */
		public static byte[] trimAudioDataHeader(byte[] audioDataWithHeader) {
			return Arrays.copyOfRange(audioDataWithHeader, 46, audioDataWithHeader.length);
		}

		/**
		 * Insert audio header to the head of the audio stream using general header.
		 * Must call the setAudioDataHeader first to setup a sample header else will throw NPE.
		 * @return
		 */
		private static byte[] audioHeader = null;
		public static byte[] insertAudioDataHeader(byte[] audioDataWithoutHeader) {
			return Util.concatByteArray(audioHeader, audioDataWithoutHeader);
		}

		/**
		 * Setup the initial header for insertAudioDataHeader to insert using a real audio sample.
		 * @param audioDataWithHeader
		 */
		public static void setAudioDataHeader(byte[] audioDataWithHeader) {
			audioHeader = Arrays.copyOfRange(audioDataWithHeader, 0, 46);
		}

		/**
		 * Return the recorded audio header.
		 * @return
		 */
		public static byte[] getAudioDataHeader() {
			return audioHeader;
		}

		/**
		 * Audio general vertex which data vertex's 'data' property field contain raw byte[] data of the audio not trimmed of header.
		 * @param audioGeneralVertex
		 */
		public static void setAudioDataHeader(Vertex audioGeneralVertex) {
			Vertex audioDataVertex = Util.traverseOnce(audioGeneralVertex, Direction.IN, DBCN.E.data, LTM.AUDIO_RAW);
			setAudioDataHeader( (byte[])audioDataVertex.getProperty(LP.data));
		}

		public static double[] audioVertexToDoubleArray(Vertex targetVertex) {
			byte[] audioData = audioVertexToByteArray(targetVertex);
			//Trim header first as the Util function doens't expect data with header intact.
			audioData = trimAudioDataHeader(audioData);
			return Util.audioByteArrayToDoubleArray(audioData);
		}

		/**
		 * General vertex where after traversing its 'data' edge to another vertex, contains 'data' property field which contains the actual
		 * audio data in binary form.
		 * @param generalVertex
		 * @return
		 */
		public static byte[] audioVertexToByteArray(Vertex generalVertex) {
			Vertex dataVertex = Util.traverseOnce(generalVertex, Direction.IN, DBCN.E.data);
			return dataVertex.getProperty(LP.data);
		}

		/**
		 * Scan the whole audio data to get its normalized distribution variance.
		 * @param targetVertex The general vertex that have link to the actual raw audio data vertex, not the actual data vertex itself.
		 * The audio data should already be trimmed of header.
		 * @return The distribution value in terms of 0~100 percent.
		 */
		public static double scanAudioDistribution (Vertex targetVertex) {
			double[] audioData = audioVertexToDoubleArray(targetVertex);
			double sum = 0;

			for (double b : audioData)
				sum += b;

			double average = sum / (double)audioData.length;
			//http://stackoverflow.com/questions/4241492/maths-range-to-percentage
			average = ( (average - -1d) / (1 - -1) ) * 100d;

			//average divide by upper bound to convert it from 0~255 scale to 0~100 scale, then multiply it by 100 to become percentage.
			//convert to double because all polyVal in the system uses double.
			if ( average <= 100d && average >= 0d )
				return average;
			else
				throw new IllegalArgumentException("Average value must be >=0 && <=100 but get: " + average) ;
		}

		/**
		 * Concatenate audio files into a single audio file and output it to the specified path.
		 * @param pythonCommandFilePath The python script path for this operation, leave "" to use default script path.
		 * @param outputPath Output path of the concatenated audio file.
		 * @param toBeConcatenatedAudioDataVertexList List of generalVertexes where we have to tranverse once more via its data edge
		 * to get to the data vertex whom data field contains the actual raw audio data.
		 */
		public static void concatenateAudioFilesByDataVertex(String pythonCommandFilePath, String outputPath
				, Queue<String>toBeConcatenatedAudioGeneralVertexList, Graph txGraph) {
			//Internally we uses python pydub library to make this work.
			//Check whether user had given use the command file path, if not use the default path.
			String commandFilePath = pythonCommandFilePath.equals("") ?
					"python resources/audioICL/isra_audioICL_concatWav.py"
					: pythonCommandFilePath;

			//Convert rid to actual vertex.
			//Saves all the audio data to file in order for the script to process, cannot pass them raw binary data directly.
			ArrayList<String> audioFilePaths = new ArrayList<String>();
			int indexCount = 0;
			for (String audioVertexRid : toBeConcatenatedAudioGeneralVertexList) {
				//Audio file path must be .wav (wave format) as all of our inputs are in wav.
				//These audioFiles are temporary and will be replaced everytime we call this function.
				String audioFilePath = "resources/audioICL/temp/dejavuConcat/concat-" + indexCount + ".wav";
				Vertex audioGeneralVertex = Util.ridToVertex(audioVertexRid, txGraph);
				Vertex audioDataVertex = Util.traverseOnce(audioGeneralVertex, Direction.IN, DBCN.E.data, LTM.AUDIO_RAW);
				try {
					FileUtils.writeByteArrayToFile(new File(audioFilePath), (byte[]) audioDataVertex.getProperty(LP.data) );
				}
				catch (IOException e) {
					throw new IllegalStateException("Write byte array to file failed", e);
				}

				audioFilePaths.add(audioFilePath);
				indexCount++;
			}
			//Append all the parameter required by the script.
			commandFilePath = commandFilePath + " " + outputPath + " " + Util.arrayListOfStringToStringSeparatedBySpace(audioFilePaths);

			//http://stackoverflow.com/questions/6295866/how-can-i-capture-the-output-of-a-command-as-a-string-with-commons-exec
			//Execute the script.
		    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		    CommandLine commandline = CommandLine.parse(commandFilePath);
		    DefaultExecutor exec = new DefaultExecutor();
		    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
		    exec.setStreamHandler(streamHandler);
		    try {
		    	exec.execute(commandline);
		    } catch (IOException e) {
		    	//If error occurs, means it is an internal script error. outputStream.toString() contains the stderr of the script output.
		    	throw new IllegalStateException("Script execution error, command: " +  commandFilePath + "; Error: " + outputStream.toString(), e);
		    }
		}

		/**
		 * Fingerprint the latest concatenated audio file so it can be used to identify(recognize) pattern within it.
		 * @param pythonCommandFilePath The python script path for this operation, leave "" to use default script path.
		 * @param concatenatedAudioFilePath The concatenated audio file path that you used as outputPath parameter for concatenateAudioFilesByDataVertex
		 */
		public static void fingerprintConcatenatedAudioFile(String pythonCommandFilePath, String concatenatedAudioFilePath) {
			//http://stackoverflow.com/questions/6295866/how-can-i-capture-the-output-of-a-command-as-a-string-with-commons-exec
			//The script support both fingerprint and recognize function, only fingerprint state should reset database to reflect the latest
			//audio fingerprint, so they don't accidentally recognize it as another audio segment from previous time segments.
			String operationType = " fingerprint ";
			String resetDatabase = " true ";

			//Check whether user had given use the command file path, if not use the default path.
			String commandFilePath = pythonCommandFilePath.equals("") ?
					"python resources/audioICL/isra_audioICL_fingerprintAndRecognize.py"
					: pythonCommandFilePath;

			//Append all the parameter required by the script.
			commandFilePath = commandFilePath + operationType + resetDatabase + concatenatedAudioFilePath;

			//http://stackoverflow.com/questions/6295866/how-can-i-capture-the-output-of-a-command-as-a-string-with-commons-exec
			//Execute the script.
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			CommandLine commandline = CommandLine.parse(commandFilePath);
			DefaultExecutor exec = new DefaultExecutor();
			PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
			exec.setStreamHandler(streamHandler);
			try {
				exec.execute(commandline);
			} catch (IOException e) {
				//If error occurs, means it is an internal script error. outputStream.toString() contains the stderr of the script output.
				throw new IllegalStateException("Script execution error: " + outputStream.toString(), e);
			}
		}

		/**
		 * Fingerprint the latest concatenated audio file so it can be used to identify(recognize) pattern within it.
		 * @param pythonCommandFilePath The python script path for this operation, leave "" to use default script path.
		 * @param audioPatternDataVertexList The audio vertex list containing actual extracted audio pattern data.
		 * @return The offset in milliseconds.
		 */
		public static ArrayList<Long> recognizePatternFromFingerprintedConcatenatedAudioFile(
				String pythonCommandFilePath, ArrayList<Vertex> audioPatternDataVertexList) {
			//http://stackoverflow.com/questions/6295866/how-can-i-capture-the-output-of-a-command-as-a-string-with-commons-exec
			//The script support both fingerprint and recognize function, only fingerprint state should reset database to reflect the latest
			//audio fingerprint, so they don't accidentally recognize it as another audio segment from previous time segments.
			//Recognize state doesn't need to reset database as it make no sense (empty database, recognize from what?)
			String operationType = " recognize ";
			String resetDatabase = " false ";

			//Check whether user had given use the command file path, if not use the default path.
			String commandFilePath = pythonCommandFilePath.equals("") ?
					"python resources/audioICL/isra_audioICL_fingerprintAndRecognize.py"
					: pythonCommandFilePath;

			//Saves all the pattern audio data to file in order for the script to process, we cannot pass them raw binary data directly.
			ArrayList<String> audioPatternFilePaths = new ArrayList<String>();
			for (int i=0; i<audioPatternDataVertexList.size(); i++) {
				//Audio file path must be .wav (wave format) as all of our inputs are in wav.
				//These audioFiles are temporary and will be replaced everytime we call this function.
				//We must add in its default audio header as they are trimmed before storage to save space.
				String audioPatternFilePath = "resources/audioICL/temp/dejavuRecognize/audioPattern-" + i + ".wav";
				try {
					FileUtils.writeByteArrayToFile(new File(audioPatternFilePath),
							ICL.Audio.insertAudioDataHeader( (byte[]) audioPatternDataVertexList.get(i).getProperty(LP.data) ) );
				}
				catch (IOException e) {
					throw new IllegalStateException("Write byte array to file failed", e);
				}

				audioPatternFilePaths.add(audioPatternFilePath);
			}

			//Append all the parameter required by the script and run them individually. Although the script can accept multiple element
			//at a time, but we are too lazy to sort their aggregated output, thus one output per time simpler processing.
			//TODO: Use its multiple element processing by appending all of the fileName at once to them to speed things up but have to
			//decipher the result independently, not implmented now.
			//Example:commandFilePath = commandFilePath + operationType + resetDatabase + Util.arrayListOfStringToStringSeparatedBySpace(audioPatternFilePaths);

			ArrayList<Long> result = new ArrayList<Long>();
			//This is similar to the fingerprint parameter style, in fact they uses the same script.
			for (int i=0; i<audioPatternFilePaths.size(); i++) {
				String finalCommandFilePath = commandFilePath + operationType + resetDatabase + audioPatternFilePaths.get(i);

				//http://stackoverflow.com/questions/6295866/how-can-i-capture-the-output-of-a-command-as-a-string-with-commons-exec
				//Execute the script.
			    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			    CommandLine commandline = CommandLine.parse(finalCommandFilePath);
			    DefaultExecutor exec = new DefaultExecutor();
			    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
			    exec.setStreamHandler(streamHandler);
			    try {
			    	exec.execute(commandline);
			    } catch (IOException e) {
			    	//If error occurs, means it is an internal script error. outputStream.toString() contains the stderr of the script output.
			    	throw new IllegalStateException("Script execution error: " + outputStream.toString(), e);
			    }

			    String outputOfScript = outputStream.toString();
			    //'None' is the library print statement indicated not match and we do not recommend to modify anything in the lib thus we just use that value.
			    if (outputOfScript.contains("None"))
			    	result.add(-1l);
			    //Else it should return a string of data about the matching process, we will fetch the time offset.
			    //{'song_id': 1, 'song_name': 'Taylor Swift - Shake It Off', 'confidence': 3948, 'offset_seconds': 30.00018, 'match_time': 0.7159781455993652, 'offset': 646L}
			    //Extract offset_seconds from it and convert it into millisec.
			    //https://github.com/worldveil/dejavu/issues/43
			    else {
			    	String offsetSeconds = outputOfScript.substring(outputOfScript.indexOf("seconds': ") + 10, outputOfScript.indexOf(", 'match") );
			        //Trim the seconds and milliseconds, ignoring the the remaining trailing milliseconds precision and trim it to millisecond (000) 3 digits.
			    	String sec = offsetSeconds.substring(0, offsetSeconds.indexOf("."));

			        //Check whether the trailing digits has enough value for us, we need 3 digits.
			    	String millisec = "";
			    	//If it doesn't have enough digits for us, we will get whatever he have, and add trailing zeros.
			    	if ( (offsetSeconds.indexOf(".") + 4) > offsetSeconds.length()) {
			    		millisec = offsetSeconds.substring(offsetSeconds.indexOf(".") + 1, offsetSeconds.length());
			    		while (millisec.length() < 3)
			    			millisec += "0";
			    	}
			    	//Else if have enough digits or even exceeds. Grab only 3 digits.
			    	else
			    		millisec = offsetSeconds.substring(offsetSeconds.indexOf(".") + 1, offsetSeconds.indexOf(".") + 4);

			        //Convert seconds into milliseconds and add in the remaining milliseconds.
			        long offsetInMillisec = (Long.parseLong(sec) * 1000) + Long.parseLong(millisec);

			        assert offsetInMillisec >= 0 : offsetInMillisec;
			        result.add(offsetInMillisec);
			    }
			}
			return result;
		}

		/**
		 * Check the audio data's type to see if they matches, if they don't, we will have to convert it manually because we might have
		 * many different encoding scheme.
		 * @return True if equivalent, false otherwise.
		 * TODO: If property doesn't exist, it is a type mismatch of data, eg the data are not raw data or visual data pair with audio data.
		 * which is fatal error and will not be toleranted.
		 */
		public static boolean checkAudioBaseType (Vertex v1, Vertex v2) {
			String v1FF = v1.getProperty(LP.audioFileFormat);
			String v2FF = v2.getProperty(LP.audioFileFormat);

			//audio bitdepth boundary. upper/lower audio bound.
			int v1UAB = v1.getProperty(LP.audioUBound);
			int v2UAB = v2.getProperty(LP.audioUBound);

			int v1LAB = v2.getProperty(LP.audioLBound);
			int v2LAB = v2.getProperty(LP.audioLBound);

			int v1FC = v1.getProperty(LP.audioFrameCount);
			int v2FC = v2.getProperty(LP.audioFrameCount);

			int v1FR = v1.getProperty(LP.audioFrameRate);
			int v2FR = v2.getProperty(LP.audioFrameRate);

			int v1C = v1.getProperty(LP.audioChannel);
			int v2C = v2.getProperty(LP.audioChannel);

			if (v1FF.equals(v2FF) && v1UAB == v2UAB && v1LAB == v2LAB && v1FC == v2FC && v1FR == v2FR && v1C == v2C)
				return true;
			return false;
		}

		/**
		 * Calculate how relevant audio data are within v1 and v2 's data.
		 * Note: The data must be in same type, we don't do type checking here, proper conversion should had been done beforehand.
		 * @param thresholdInPolyVal Allowed offset percentage that will still be considered acceptable.
		 * @return A value between 0~100 to indicate how relevant they are. 100 is exact match, 0 is no matching point at all.
		 */
		@Deprecated
		public static double getRelevancyAudioData(Vertex v1, Vertex v2, double thresholdInPolyVal) {
			byte[] v1Data = audioVertexToByteArray(v1);
			byte[] v2Data = audioVertexToByteArray(v2);

			//Expects their type are already matched.
			//convert it from percentage to actual data ranged value.
			double convertedThreshold = Util.polyValDenormalize(0, 255, thresholdInPolyVal);
			int matchingData = 0;
			int largerDataCount = v1Data.length > v2Data.length ? v1Data.length : v2Data.length;
			int smallerDataCount = v1Data.length < v2Data.length ? v1Data.length : v2Data.length;
			//4 byte per data, simulating float.
			for (int i=0; i<smallerDataCount; i+=4) {
				int count = 0;
				count += (v1Data[i] & 0xFF) - (v2Data[i] & 0xFF);
				count += (v1Data[i + 1] & 0xFF) - (v2Data[i + 1] & 0xFF);
				count += (v1Data[i + 2] & 0xFF) - (v2Data[i + 2] & 0xFF);
				count += (v1Data[i + 3] & 0xFF) - (v2Data[i + 3] & 0xFF);

				if (count <= thresholdInPolyVal)
					matchingData++;
			}

			//For those data which are not present, mark them as not match.
			matchingData -= largerDataCount - smallerDataCount;

			//return the magnitude of relevancy in terms of percentage.
			//Divide by 4 the smallerDataCount as we count 4 data as 1.
			return ( (double) matchingData / (double)(smallerDataCount/4) ) * 100d;
		}

		/**
		 * Get one data from the data vertex specified by frame. Get individual audio binary data.
		 * TODO: allowed varag input to fetch more, to reduce overhead of calling db.
		 * @param targetVertex The vertex that contain the raw audio data.
		 * @param channel 1 mono or 2 stereo, never 0.
		 * @param frameIndex The specific index of data to be fetched.
		 * @return The data at the specified index within data vertex.
		 */
		public static float[] getIndividualAudioData(Vertex targetVertex, int channel, int frameIndex) {
			byte[] data = audioVertexToByteArray(targetVertex);

			//Check whether ISRA had specified the wrong channel despite the warning.
			int realChannel = targetVertex.getProperty(LP.audioChannel);
			if (channel > realChannel || channel < realChannel) {
				throw new IndexOutOfBoundsException("User specified channel:" + channel + "; out of acceptable bound:" + realChannel);
			}

			//eg  01 23 45 67 89 as the data array. given index 2, you get 2 * 2 (channel) + 0 (i first iteration) = [4]; which is number 4.
			float result[] = new float[channel];
			for (int i=0; i<channel; i++) {
				result[i] = data[frameIndex * channel + i];
			}

			return result;
		}

		/**
		 * Scan through the data array and returns all the frame index within the specified u/l range. Massive query function.
		 * @param targetVertex The vertex that contain the raw audio data.
		 * @param channel 1 mono or 2 stereo, never 0.
		 * @param lBound Lower boundary, scan within the radius of upper and lower boundary. -1~1 max
		 * @param uBound Upper boundary, scan within the radius of upper and lower boundary. -1~1 max
		 * @return An array that contain all the frame index within the specified radius. Empty if nothing matches.
		 */
		public static ArrayList<Integer> getAudioDataInRange (Vertex targetVertex, int channel, double lBound, double uBound) {
			//check whether ISRA had specified the wrong channel despite the warning.
			if ( !(channel == 0 || channel == 1))
				throw new IllegalArgumentException("Channel must be either 1 mono OR 2 stereo.");
			if (lBound > uBound)
				throw new IllegalArgumentException("Lower bound larger than upper bound.");
			if (uBound < lBound)
				throw new IllegalArgumentException("Upper bound smaller than lower bound.");

			byte[] audioData = audioVertexToByteArray(targetVertex);

			ArrayList<Integer> result = new ArrayList<Integer>();

			//if the data is within u/l boundary specified by ISRA, then store it into array.
			for (int i=0; i<audioData.length; i++) {
				int data = audioData[i] & 0xFF;
				if (data >= lBound && data <= uBound)
					result.add(i);
			}
			return result;
		}
		//TODO: Append the data to global chained link. Then dispose this local data before the beginning of next execution.
	}

	//Includes motor, stepper and anything that moves
	public static class Movement {
		/**
		 * Scan the movement data to get its normalized distribution variance.
		 * @param targetVertex The general vertex that have link to the actual raw movement data vertex, not the actual data vertex itself.
		 * @return The distribution value in terms of 0~100 percent.
		 */
		public static double scanMotorDistribution(Vertex targetVertex) {
			//get motor state, convert it to polyVal distribution mode (0~100%) and return.
			//TODO: same problem as weight problem of STM.
//			double motorPosition = targetVertex.getProperty(LP.motorPosition);
//			double motorPWM = targetVertex.getProperty(LP.motorPWM);
//			int motorState = targetVertex.getProperty(LP.motorState);

			//Traverse to its data vertex and get the data directly without any further computation.
			return Util.traverseOnce(targetVertex, Direction.IN, DBCN.E.data, LTM.MOVEMENT).getProperty(LP.data);
		}
	}

	//electric skins, thermometer, calibrator.
	public static class MiscSensor {

	}
}
