package crawler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import ICL.ICL;
import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import logger.Logger;
import logger.Logger.CLA;
import logger.Logger.Credential;
import logger.Logger.LVL;
import stm.DBCN;
import stm.LTM;
import stm.STMClient;
import utilities.Util;

/*
 * Crawler Function Convention is:
 * Without special modifier: stateless calculation. Modifies only given parameter and do not make any transaction to database.
 * TxL: Transaction in code, modify only the parameter fed in. Basically stateless. (LOCAL)
 * TxE: Transaction in code that modify local and expand to external interface. (EXTERNAL)
 * TxF: Transaction in code and lead to next phrase of work, that post states. (FORWARDING)
 */
/**
 * Identify, classify and create new pattern for raw data.
 * WM can assign patterns for us to identify, and those data (raw input data) who are left unidentified will be identified via
 * ROI grouping model, different implementation for each type of data, aka the 'crude identification'.
 *
 * For audio part, it contains states that should be reused, thus it should never be recreated if consecutive progress is wanted,
 * which is most of the time, thus you should just create one instance and keep reuse it.
 */
public class RawDataICL {
	private long dbErrMaxRetryCount;
	private long dbErrRetrySleepTime;
	private Logger logger;
	private Credential logCredential;
	private boolean loggerSet;

	public RawDataICL(long dbErrMaxRetryCount, long dbErrRetrySleepTime, Logger logger, Credential logCredential) {
		this.dbErrMaxRetryCount = dbErrMaxRetryCount;
		this.dbErrRetrySleepTime = dbErrRetrySleepTime;
		this.logger = logger;
		this.logCredential = logCredential;
		if (logger != null && logCredential != null)
			loggerSet = true;
		else
			loggerSet = false;
	}

	/**
	 * Visual ICL core logic.
	 * @param generalVertex
	 * @param globalDist
	 * @param globalVariance
	 * @param txGraph
	 */
	public void visualICLTxL(Vertex generalVertex, double globalDist, double globalVariance, Graph txGraph) {
		//TODO: get relevancy visual data and check type before compairing.
		Mat srcImgMat = ICL.Visual.visualVertexToMat(generalVertex);

		int imgWidth = srcImgMat.width();
		int imgHeight = srcImgMat.height();
		//We will implement a count system to calculate how many pixels we had identified, if it exceed 50% on given
		//identified pattern then we wont use general contour pattern generation which is expensive.
		int sourceImgTotalPixel = imgWidth * imgHeight;
		int processedPixelCount = 0;
		assert imgWidth > 0 && imgHeight > 0 : "imgWidth:" + imgWidth + " ; imgHeight:" + imgHeight + " ; Both should be > 0.";

		//A record to indicate whether certain part of the source image had been matched by anything or not.
		//Matched means identified. And also initialize the whole matrix to 0.
		//http://stackoverflow.com/questions/12231453/syntax-for-creating-a-two-dimensional-array  State that default is init to 0.
		//http://stackoverflow.com/questions/25642532/opencv-pointx-y-represent-column-row-or-row-column
		//http://stackoverflow.com/questions/32971241/how-to-get-image-width-and-height-in-opencv
		//OR new int [srcImgMat.cols][srcImgMat.rows]
		int[][] processedMat = new int[imgWidth][imgHeight];

		//Get ROI from the image based on current polyVal, add a range of 10% as allowance.
		double imgAllowance = Util.polyValDenormalize(0, 255, globalVariance);
		double convertedGlobalDist = Util.polyValDenormalize(0.0, 100.0, globalDist);
		double lBound = (convertedGlobalDist - imgAllowance) > 0 ? convertedGlobalDist - imgAllowance : 0;
		double uBound = (convertedGlobalDist + imgAllowance) < 255 ? convertedGlobalDist + imgAllowance : 255;
		assert imgAllowance >= 0d && imgAllowance <= 255d : "Range 0 ~ 255, get:" + imgAllowance;
		assert lBound >= 0d && lBound <= 255d && uBound >= 0d && uBound <= 255d : "Range 0 ~ 255, get lBound:" + lBound + ", uBound" + uBound;

		//Note: we expect DB to gives us the result in sorted order.
		//Get latest pattern expected by WM for recursive feedback purposes.
		ArrayList<Vertex> patternList = STMClient.getLatestRawDataPatternByGCA("Visual", txGraph);

		//http://stackoverflow.com/questions/28759253/how-to-crop-the-internal-area-of-a-contour
		//http://rkdasari.com/2013/11/09/homography-between-images-using-opencv-for-android/
		//Detect once for given pattern and once for screen, then compare their pattern in scale invariant way. BRISK is scale invariant.
		//Compute original first, then compute against the template patterns.
		MatOfKeyPoint matOfKeyPointsOriginal = new MatOfKeyPoint();
		FeatureDetector brisk = FeatureDetector.create(FeatureDetector.BRISK);
		brisk.detect(srcImgMat, matOfKeyPointsOriginal);
		DescriptorExtractor extractor = DescriptorExtractor.create(DescriptorExtractor.BRISK);
		Mat descriptorOriginal = new Mat();
		extractor.compute(srcImgMat, matOfKeyPointsOriginal, descriptorOriginal);
		DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);

		//Calculate relevancy for predefined pattern only fetched from STM, generated by crude grouping or GCA composite pattern.
		for (Vertex v : patternList) {
			assert v.getCName().equals(DBCN.V.LTM.rawDataICL.visual.cn) : v;
			//Basically the same operation as above (Extract and match pattern).
			MatOfKeyPoint matOfKeyPointsTemplate = new MatOfKeyPoint();
			Mat template = ICL.Visual.byteArrayToMat( (byte[])(v.getProperty(LP.data)) );

			brisk.detect(template, matOfKeyPointsTemplate);

			Mat descriptorTemplate = new Mat();
			extractor.compute(template, matOfKeyPointsTemplate, descriptorTemplate);
			MatOfDMatch descriptorMatches = new MatOfDMatch();
			matcher.match(descriptorOriginal, descriptorTemplate, descriptorMatches);

			//Seek the best match by discarding some bad matches based on their relative distance against all other points.
			double max_dist = 0; double min_dist = 100;
			List<DMatch> matchesList = descriptorMatches.toList();

			//If there is no matches, continue to next pattern.
			if (matchesList.isEmpty())
				continue;

			//Quick calculation of max and min distances between keypoints.
			for (DMatch dm : matchesList) {
				Double dist = (double) dm.distance;
				if( dist < min_dist ) min_dist = dist;
				if( dist > max_dist ) max_dist = dist;
			}

			LinkedList<DMatch> good_matches = new LinkedList<DMatch>();

			//*3 has no specific meaning, just result in better accuracy during test.
			for (DMatch dm : matchesList) {
				if (dm.distance < min_dist * 3) {
					good_matches.addLast(dm);
				}
			}

			//If there is no any good match, skip this pattern to treat it as not match.
			if (good_matches.isEmpty())
				continue;

			MatOfDMatch gm = new MatOfDMatch();
			gm.fromList(good_matches);

			//http://stackoverflow.com/questions/12937490/how-to-access-points-location-on-opencv-matcher
			List<KeyPoint> keypoints_objectList = matOfKeyPointsOriginal.toList();
			LinkedList<Point> matchedDescriptorPoint = new LinkedList<Point>();
			for (int i=0; i<good_matches.size(); i++) {
				matchedDescriptorPoint.addLast(keypoints_objectList.get(good_matches.get(i).queryIdx).pt);
			}

			//Create a bounding box around the selected points.
			MatOfPoint points = new MatOfPoint();
			points.fromList(matchedDescriptorPoint);
			Rect ROIRect = Imgproc.boundingRect(points);

			//Compute their relevance, if not match, then don't record it down.
			//Original image extract the best potential matching point by the rectangle and form a new Mat.
			//Scalar has 4 value, 0~2 is bgr, 3 is alpha, as alpha will never be used, and maybe in the future it may
			//become grayscale(source image), thus we utilize channel count to be safe.
			Mat extractedOriginal = new Mat(srcImgMat, ROIRect);
			Scalar meanOriginal = Core.mean(extractedOriginal);
			Scalar meanTemplate = Core.mean(template);
			double totalMeanDifferences = 0.0d;
			for (int i=0; i< srcImgMat.channels(); i++) {
				//Absolute value of mean differences.
				totalMeanDifferences += Math.abs(meanOriginal.val[i] - meanTemplate.val[i]);
			}

			//We want the average of those mean points, multiple by 2 as there is 2 type of variable, mean original and
			//mean template, thus the addition of their channel will the individual channel count * 2.
			//Smaller than image allowance means within the variance of threshold, means the difference is small.
			if (totalMeanDifferences / srcImgMat.channels() * 2 <= imgAllowance) {
				Vertex patternGeneralVertex = null;
				//Commit retry model.
				boolean txError = true;
				int txRetried = 0;
				while (txError) {
					if (txRetried > dbErrMaxRetryCount) {
						throw new IllegalStateException("Failed to complete transaction after number of retry:"
								+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
					}
					else if (txError) {
						if (txRetried != 0)
							Util.sleep(dbErrRetrySleepTime);
						txRetried++;
					}
					txGraph.begin();

					generalVertex = Util.vReload(generalVertex, txGraph);

					//Create 2 vertex to store the result, and make 'occurrence' edge to the original pattern to mark it as its descendant.
					patternGeneralVertex = txGraph.addVertex(DBCN.V.general.rawDataICL.visual.cn
							, DBCN.V.general.rawDataICL.visual.cn);
					Vertex patternDataVertex = txGraph.addVertex(DBCN.V.LTM.rawDataICL.visual.cn, DBCN.V.LTM.rawDataICL.visual.cn);

					//We expect the DB to interpret jpg files as binary.
					MatOfByte bytemat = new MatOfByte();
					Imgcodecs.imencode(".jpg", extractedOriginal, bytemat);
					patternDataVertex.setProperty(LP.data, bytemat.toArray());
					patternDataVertex.setProperty(LP.imgX, ROIRect.x);
					patternDataVertex.setProperty(LP.imgY, ROIRect.y);
					patternDataVertex.addEdge(DBCN.E.data, patternGeneralVertex);

					//the vertex that this vertex origin from is the raw data vertex that contains the raw data.
					patternGeneralVertex.addEdge(DBCN.E.parent, generalVertex);

					//Add edge to its original pattern as occurrence.
					Vertex ICLPatternGeneralVertex = Util.traverseOnce(v, Direction.OUT, DBCN.E.data, LTM.VISUAL_ICL);
					patternGeneralVertex.addEdge(DBCN.E.occurrence, ICLPatternGeneralVertex);

					txError = txGraph.finalizeTask(true);
				}

				//Start a new transaction to avoid retry induced data inconsistency at GCA site. To guarantee idempotent.
				txGraph.begin();
				//TODO: should be the polyVal of the dist, not the globalDist, but uses that for simplicity.
				patternGeneralVertex.setProperty(LP.polyVal, globalDist);
				STMClient.addDist((double) patternGeneralVertex.getProperty(LP.polyVal), DBCN.V.general.rawDataICL.visual.cn, txGraph);

				//Add it to the GCA so it will be visible to the DM system.
				STMClient.rawDataICLAddToGCAQueue(patternGeneralVertex, txGraph);
				txGraph.finalizeTask();

				//Record how many pixels is in the template, as we had completed it, so we add its count to the total
				//number of processed pixels.
				processedPixelCount += template.width() * template.height();

				//http://docs.opencv.org/java/2.4.9/org/opencv/core/Rect.html
				//Mark the ROI region as matched.
				for (int y=ROIRect.y; y<ROIRect.y + ROIRect.height; y++) {
					for (int x=ROIRect.x; x<ROIRect.x + ROIRect.width; x++) {
						//Add in relative x and y to reflect the actual coordinate.
						processedMat[x][y]++;
					}
				}
				if (loggerSet)
					logger.log(logCredential, LVL.INFO, CLA.NORM, "RawDataICL identified pattern, size:" + template.width() * template.height());
			}
		}

		if (patternList.size() != 0) {
			if (loggerSet)
				logger.log(logCredential, LVL.INFO, CLA.NORM, "At rawDataICL: Visual ICL pattern received. Size: " + patternList.size());
		}

		//After all of the specified pattern been processed, we will do some free style contour based computing
		//to and randomness to the system input (inclination guide mutation).
		Mat tempMat = new Mat();
		//Calculate and generate ROI list based on threshold given by globalDist.
		//http://stackoverflow.com/questions/18581633/fill-in-and-detect-contour-rectangles-in-java-opencv;
		Mat roi = new Mat();
		Core.inRange(srcImgMat, new Scalar(lBound, lBound , lBound), new Scalar(uBound, uBound ,uBound), roi);
		//3x3 has no specific meaning, just result in better accuracy during test.
		Mat erode = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3,3));
		Imgproc.erode(roi, tempMat, erode);
		Imgproc.dilate(roi, tempMat, erode);
		Imgproc.dilate(roi, tempMat, erode);

		List<MatOfPoint> contours = new ArrayList<>();

		Imgproc.findContours(tempMat, contours, new Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
		Imgproc.drawContours(tempMat, contours, -1, new Scalar(255,255,0));

		for (int i=0; i<contours.size(); i++) {
			//Divide by 2 to save computing power, if done half of the image already, skip. Can use any number factor.
			//TODO: You may switch on and off this feature if computing power is high, switch it off by default to learn
			//maximum amount of new data. Turn off by commenting.
//			if (processedPixelCount > sourceImgTotalPixel / 2)
//				break;

			//Get bounding rect of contour then draw the rect on a new output mat.
			//http://docs.opencv.org/2.4/doc/tutorials/introduction/desktop_java/java_dev_intro.html
			Rect boundingRect = Imgproc.boundingRect(contours.get(i));
			//http://answers.opencv.org/question/29260/how-to-save-a-rectangular-roi/
			//Extract the rectangle area from the original image which has color, instead of the processedMat, which is grayscale.
			Mat outputRect = new Mat(srcImgMat, boundingRect);

			//http://answers.opencv.org/question/97388/featuredetector-minimum-template-size/   NO REPLY.
			//We found that any image smaller than 6x6 will raise an assertion error:
			//OpenCV Error: Assertion failed (dsize.area() > 0 || (inv_scale_x > 0 && inv_scale_y > 0)) in resize,
			//file ...../opencv-3.0.0/modules/imgproc/src/imgwarp.cpp, line 3209
			//Thus we simply just skip that pattern if they falls under this category.
			if (outputRect.width() < 6 || outputRect.height() < 6)
				continue;

			/*
			 * Operation priority are:
			 * Given pattern matching first -> Scramble random pattern for remaining unrecognized region.
			 * Check whether it is covered by given pattern or not, if not then fuzzy group them, then attempt to
			 * match them with global exp to ensure we don't accidentally mark any pattern as new, which will make it
			 * unable to use any previously learned data and exp.
			 * If all of them fails, mark them as stray new pattern.
			 */

			//Check whether it has been identified before or not.
			int overlapPixelCount = 0;
			for (int y=boundingRect.y; y<boundingRect.y + boundingRect.height; y++) {
				for (int x=boundingRect.x; x<boundingRect.x + boundingRect.width; x++) {
					//Add in relative x and y to reflect the actual coordinate.
					if (processedMat[x][y] != 0)
						overlapPixelCount++;
				}
			}
			//If the matching percentage is larger than 75%, treat it as matched, thus do nothing and skip this pattern.
			if ( ( (double)overlapPixelCount / (double)(boundingRect.width * boundingRect.height) ) * 100d > 75d)
				continue;

			//Mark the ROI region as processed.
			for (int y=boundingRect.y; y<boundingRect.y + boundingRect.height; y++) {
				for (int x=boundingRect.x; x<boundingRect.x + boundingRect.width; x++) {
					//Add in relative x and y to reflect the actual coordinate.
					processedMat[x][y]++;
				}
			}

			//TODO: Bruteforce ICL in the future to identify pattern that were recognized before but not presented via expectation
			//(via pattern feedback) by WM.
			boolean matchFromGlobalExpMemory = false;

			//Else nothing matches it, means it is an alien, crude identify it.
			if (!matchFromGlobalExpMemory) {
				Vertex patternGeneralVertex = null;
				//Commit retry model.
				boolean txError = true;
				int txRetried = 0;
				while (txError) {
					if (txRetried > dbErrMaxRetryCount) {
						throw new IllegalStateException("Failed to complete transaction after number of retry:"
								+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
					}
					else if (txError) {
						if (txRetried != 0)
							Util.sleep(dbErrRetrySleepTime);
						txRetried++;
					}
					txGraph.begin();

					generalVertex = Util.vReload(generalVertex, txGraph);

					//Create 2 vertex to store the result, as it doens't match any given pattern, it is not entitled to have an
					//'occurrence' edge toward any other pattern as he is new and stray without origin.
					patternGeneralVertex = txGraph.addVertex(DBCN.V.general.rawDataICL.visual.cn
							, DBCN.V.general.rawDataICL.visual.cn);
					Vertex patternDataVertex = txGraph.addVertex(DBCN.V.LTM.rawDataICL.visual.cn, DBCN.V.LTM.rawDataICL.visual.cn);

					//We expect the DB to interpret jpg files as binary.
					MatOfByte bytemat = new MatOfByte();
					Imgcodecs.imencode(".jpg", outputRect, bytemat);
					patternDataVertex.setProperty(LP.data, bytemat.toArray());
					//X Y coordinate of original
					patternDataVertex.setProperty(LP.imgX, boundingRect.x);
					patternDataVertex.setProperty(LP.imgY, boundingRect.y);
					patternDataVertex.addEdge(DBCN.E.data, patternGeneralVertex);

					//the vertex that this vertex origin from is the raw data vertex that contains the raw data.
					patternGeneralVertex.addEdge(DBCN.E.parent, generalVertex);

					txError = txGraph.finalizeTask(true);
				}

				//Start a new transaction to avoid retry induced data inconsistency at GCA site. To guarantee idempotent.
				txGraph.begin();
				//TODO: should be the polyVal of the dist, not the globalDist, but uses that for simplicity.
				patternGeneralVertex.setProperty(LP.polyVal, globalDist);
				STMClient.addDist((double) patternGeneralVertex.getProperty(LP.polyVal), DBCN.V.general.rawDataICL.visual.cn, txGraph);

				//Add general vertex to GCA-able space.
				STMClient.rawDataICLAddToGCAQueue(patternGeneralVertex, txGraph);
				txGraph.finalizeTask();
			}
			processedPixelCount += outputRect.width() * outputRect.height();
			if (loggerSet)
				logger.log(logCredential, LVL.INFO, CLA.NORM,
					"RawDataICL Stray pattern registered. Size:" + outputRect.width() * outputRect.height());
		}
	}

	/**
	 * Used by audio ICL random pattern extraction, as data keeps add in, the index will be pushed backward, and after processing, the index
	 * will be moved forward. This is to indicate the last point we had completed processing so we don't produce duplicate ICL patterns for
	 * the same data over and over again until it goes expired (replaced by new data).
	 */
	private int audioFullSampleStartReadingFromIndex = 0;
	/**
	 * Used by audio ICL to record which byte of data had been processed, increment by 1 every time it got processed.
	 * Equivalent to real audio data' index mapping, so when audio data got updated (dispose old data and append new data at the end),
	 * this list gets updated as well (replace old data with preceding data and for the new data input, leave them as 0, not processed).
	 */
	private ArrayList<Integer> audioProcessedIndex = new ArrayList<Integer>();

	//Each frame is 50ms, 20 frame 1 sec, 200 frame 10 sec. The latestAudioFrameList stores the RID of the latest frames only instead of
	//the actual data.
	private final int maximumInMemoryAudioFrame = 200;
	private Queue<String> latestAudioFrameList = new LinkedList<String>();

	//TODO: we omitted the parallel design here. You might want to parallelize it to enable more pattern identification.
	/*
	 * Audio vertex for real time update will be posted to STM by device. Then STM stores a list.
	 * When crawler ask for it, he will pass that list of audio data's general vertex's RID to the crawler, crawler then does his job.
	 * The targetVertex given here is the last audio vertex received before shutdown.
	 * STM deliberately send this to crawler. Crawler has no access to real-time audio file without consulting STM for it.
	 *
	 * It will only halt if: The system calls for shutdown, crawler node crash,
	 *
	 * NOTE: Audio data will be separated into 2 part for minimalist version. 1 is pattern changes, another is actual position.
	 * As we omitted differentiation functions, changes cannot be calculated naturally by him, thus we calculate these for him.
	 * Both of these data are to be stored together as 1 data vertex.
	 *
	 * TODO: We missed on the if pattern matched, skip as well the new pattern extraction procedure, as it has already been done,
	 * it would had already created an occurrence pattern to it, equivalent to new pattern generation.
	 */
	/**
	 * Audio ICL core logic.
	 * @param generalVertex
	 * @param globalDist
	 * @param txGraph
	 */
	public void audioICLTxL(Vertex generalVertex, double globalDist, Graph txGraph) {
		//If the latest audio file list had already reached the stated maximum, we remove its head and append new data vertex rid
		//at its back to update the whole list.  Else just add it in until it reached the specified maximum.
		if (latestAudioFrameList.size() >= maximumInMemoryAudioFrame) {
			latestAudioFrameList.poll();
			latestAudioFrameList.offer(generalVertex.getRid());
			int audioLength = ICL.Audio.audioVertexToDoubleArray(generalVertex).length;
			//Move the processed point backward as new data comes in, to allow code below to start reading from
			//the correct check point in order to avoid recomputing the same data thus yield many duplicate pattern.
			//By this it will skip the processed part thus no duplicate pattern, all fresh.
			//-1 to show index length instead of actual length.
			audioFullSampleStartReadingFromIndex -= audioLength - 1;
			//Move the processed index forward, then mark the trailing data as 0 as they were never processed before (new).
			for (int i=0; i<audioProcessedIndex.size() - audioLength; i++) {
				audioProcessedIndex.set(i, audioProcessedIndex.get(i+audioLength));
			}
			for (int i=audioProcessedIndex.size() - audioLength; i<audioProcessedIndex.size(); i++) {
				audioProcessedIndex.set(i, 0);
			}
		}
		else {
			latestAudioFrameList.offer(generalVertex.getRid());
			//Add more index to the processed progress recording array to adhere to the mapping of the newly expanded data length.
			int audioLength = ICL.Audio.audioVertexToDoubleArray(generalVertex).length;
			for (int i=0; i<audioLength; i++)
				audioProcessedIndex.add(0);
		}
		String concatCommandScriptFilePath = "";
		String concatenatedOutputFilePath = "resources/audioICL/temp/dejavuConcat/concat.wav";
		//Concatenate all those frames into a single file for fingerprinting purposes.
		ICL.Audio.concatenateAudioFilesByDataVertex(concatCommandScriptFilePath, concatenatedOutputFilePath, latestAudioFrameList, txGraph);

		//Fingerprint the latest concatenated audio file.
		String fingerprintCommandScriptFilePath = "";
		ICL.Audio.fingerprintConcatenatedAudioFile(fingerprintCommandScriptFilePath, concatenatedOutputFilePath);

		//Pair against all the ICL pattern given to us with the just fingerprinted latest audio data.
		//Note that fetchedDemandedPatternVertexList AND patternMatches are of same size and they have the same mapping of one to one.
		ArrayList<Vertex> fetchedDemandedPatternVertexList = STMClient.getLatestRawDataPatternByGCA("Audio", txGraph);
		String recognizeCommandScriptFilePath = "";
		ArrayList<Long> patternMatchesOffset = ICL.Audio.recognizePatternFromFingerprintedConcatenatedAudioFile(
				recognizeCommandScriptFilePath, fetchedDemandedPatternVertexList);
		assert fetchedDemandedPatternVertexList.size() == patternMatchesOffset.size()
				: fetchedDemandedPatternVertexList.size() + " " + patternMatchesOffset.size();

		//For each matches, setup their edges properly.
		for (int i=0; i<patternMatchesOffset.size(); i++) {
			//Only continue if they are matches. -1l means not match.
			if (patternMatchesOffset.get(i) != -1l) {
				byte[] audioPatternData = null;

				Vertex patternGeneralVertex = null;

				//Commit retry model.
				boolean txError = true;
				int txRetried = 0;
				while (txError) {
					if (txRetried > dbErrMaxRetryCount) {
						throw new IllegalStateException("Failed to complete transaction after number of retry:"
								+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
					}
					else if (txError) {
						if (txRetried != 0)
							Util.sleep(dbErrRetrySleepTime);
						txRetried++;
					}
					txGraph.begin();

					generalVertex = Util.vReload(generalVertex, txGraph);

					Vertex fetchedDemandedPatternVertex = fetchedDemandedPatternVertexList.get(i);
					assert fetchedDemandedPatternVertex.getCName().equals(DBCN.V.LTM.rawDataICL.visual.cn)
					: fetchedDemandedPatternVertex;

					//Create 2 vertex to store the result, and make 'occurrence' edge to the original pattern to mark it as its descendant.
					patternGeneralVertex = txGraph.addVertex(DBCN.V.general.rawDataICL.audio.cn
							, DBCN.V.general.rawDataICL.audio.cn);
					Vertex patternDataVertex = txGraph.addVertex(DBCN.V.LTM.rawDataICL.audio.cn, DBCN.V.LTM.rawDataICL.audio.cn);

					audioPatternData = fetchedDemandedPatternVertex.getProperty(LP.data);
					patternDataVertex.setProperty(LP.data, audioPatternData);
					//Get the beginning timestamp of the sample that used to pair against the pattern, then add in the offset
					//to reflect correctly the absolute time of this pattern regarding to real world absolute time.
					String firstAudioVertexRid = latestAudioFrameList.peek();
					long absoluteStartTimestamp = Util.ridToVertex(firstAudioVertexRid, txGraph).getProperty(LP.timeStamp);
					patternDataVertex.setProperty(LP.audioAbsTimestamp, absoluteStartTimestamp + patternMatchesOffset.get(i));
					patternDataVertex.addEdge(DBCN.E.data, patternGeneralVertex);

					//the vertex that this vertex origin from is the raw data vertex that contains the raw data.
					patternGeneralVertex.addEdge(DBCN.E.parent, generalVertex);

					//Add edge to its original pattern as occurrence.
					Vertex ICLPatternGeneralVertex = Util.traverseOnce(fetchedDemandedPatternVertex, Direction.OUT, DBCN.E.data, LTM.AUDIO_ICL);
					patternGeneralVertex.addEdge(DBCN.E.occurrence, ICLPatternGeneralVertex);

					txError = txGraph.finalizeTask(true);
				}

				//Start a new transaction to avoid retry induced data inconsistency at GCA site. To guarantee idempotent.
				txGraph.begin();
				//TODO: should be the polyVal of the dist, not the globalDist, but uses that for simplicity.
				patternGeneralVertex.setProperty(LP.polyVal, globalDist);
				STMClient.addDist((double) patternGeneralVertex.getProperty(LP.polyVal), DBCN.V.general.rawDataICL.audio.cn, txGraph);

				//Add general vertex to GCA-able space.
				STMClient.rawDataICLAddToGCAQueue(patternGeneralVertex, txGraph);
				txGraph.finalizeTask();

				//Mark those recognized pattern as processed.
				//Convert time into index. 44100hz, means 44100 index per 1000ms.
				long timeOffset = patternMatchesOffset.get(i);
				int patternLength = audioPatternData.length;
				int samplePerSec = 44100;
				//Convert time into hz index. / 1000ms to get the ratio, then * sampleSizePerSec to convert it to hz (index).
				int startingIndex = (int)(((double)timeOffset / 1000d) * (double)samplePerSec);
				//Trim the index to avoid out of bound error due to tiny underflow or overflow.
				if (startingIndex < 0)
					startingIndex = 0;
				if (startingIndex >= audioProcessedIndex.size())
					startingIndex = audioProcessedIndex.size() -1;
				for (int processedIndex=startingIndex; processedIndex<patternLength; processedIndex++) {
					//If the pattern exceed current available data length, possible as we don't have to match it as a whole
					//to mark it as pass, thus the data may be not inbound yet, thus we will ignore them.
					if (processedIndex == audioProcessedIndex.size() - 1)
						break;
					//Else we just increment it by 1 to mark it as had just been processed once more.
					audioProcessedIndex.set(processedIndex, audioProcessedIndex.get(processedIndex) + 1);
				}

				if (loggerSet)
					logger.log(logCredential, LVL.INFO, CLA.NORM,
						"RawDataICL audio pattern matched. Size: " + patternLength);
			}
		}

		//Recognize new stray audio data pattern from the input to broaden knowledge.
		//Get the concatenated audio file.
		/*
		 * We uses home brew algorithm to do the pattern separation:
		 * Low Band Cutoff Pattern Extraction:
		 * Create a band(line) at the -0.1 position (or any position, larger means less pattern produced, never exceed -1).
		 * For each data segment passes through the line, it will be recorded as a intersect point.
		 * For each of those point there will be a heading (heading toward up, down or maintain straight).
		 * Iterate through all those intersect points and if they are consecutive 2 heading down, we will treat them as a signal.
		 * Then we will have many of those signal segments, each of them becomes an individual pattern.
		 *
		 * Look at audioLowBandExtractPatternSampleOutput.png for sample output.
		 */
		Path path = Paths.get(concatenatedOutputFilePath);
		try {
			byte[] data = Files.readAllBytes(path);
			double[] fullAudioData = Util.audioByteArrayToDoubleArray( ICL.Audio.trimAudioDataHeader(data) );

			//Low band cut off.
			//3 type heading declaration.
			final int HEADING_UP = 0;
			final int HEADING_DOWN = 1;
			final int MAINTAIN = 2;
			ArrayList<java.awt.Point> intersectPoint = new ArrayList<java.awt.Point>();
			ArrayList<Integer> intersectState = new ArrayList<Integer>();

			//cutOffThreshold can only be negative! As it is low band, thus negative.
			double cutOffThreshold = -0.1;
			assert cutOffThreshold < 0;
			//-1 as we will do +1 to get to next point for 2 point calculation, thus -1 to avoid out of bound.
			//Start from the checkpoint (last point that processes end) to avoid recomputing the point that had already been done.
			for (int i=audioFullSampleStartReadingFromIndex; i<fullAudioData.length - 1; i++) {
				//Check 2 point, there can be 3 condition, 6 possible orientation.
				//View Audio_lowBand_cutoff_peakDetection_possibleSituation_overview.jpeg.
				double d1 = fullAudioData[i];
				double d2 = fullAudioData[i+1];

				//Up to Down (Heading downward). eg d1 = 0.3, d2 = -0.4
				if (d1 > cutOffThreshold && d2 < cutOffThreshold) {
					intersectPoint.add(new java.awt.Point(i, i+1));
					intersectState.add(HEADING_DOWN);
				}
				//Down to Up (Heading upward). eg d1 = -0.4, d2 = 0.3
				else if (d1 < cutOffThreshold && d2 > cutOffThreshold) {
					intersectPoint.add(new java.awt.Point(i, i+1));
					intersectState.add(HEADING_UP);
				}
				//On the same line as the threshold (equal). eg d1 = -0.1, d2 = -0.1, threshold = -0.1
				else if (d1 == d2 && d1 == cutOffThreshold) {
					intersectPoint.add(new java.awt.Point(i, i+1));
					intersectState.add(MAINTAIN);
				}
				//Down to up, up to down or equal but doesn't pass through or touches the cut off line.
				else {
					;
				}
			}

			//Begin grouping them into individual signals. We want a DOWN, UP, DOWN 3 step sequence.
			//So instead of 2 step (end up like a bum), it becomes a stylish square root symbol + a downward straight tail.
			//Note the first beginning signal will be longer than all other as it begins at random.
			ArrayList<java.awt.Point> groupedSignal = new ArrayList<java.awt.Point>();
			boolean down = false;
			for (int i=0; i<intersectPoint.size(); i++) {
				if (intersectState.get(i) == HEADING_DOWN) {
					if (!down) {
						down = true;
					}
					//Already down once. Thus this is second down (already up once so it can come down again).
					//3 step done, also mean it is the end of the signal.
					else {
						//One's end is another's start, always use end point instead of start point so it will not leave a trailing unused
						//end point coordinate at the really end.

						//It is expected that the grouped signal total output size will be greater than the total output size of the original result
						//as the duplicate the starting point of each signal to make it jointed. Else it will be broken and not contagious.
						//At intersect point index there, we used the end index twice to make sure it has no hole, thus added in 1 additional element
						//for each iteration (pattern).
						//Update the checkpoint (last point processed) so we will not compute it again next time.
						int intersectPointEndIndex = intersectPoint.get(i).y;
						groupedSignal.add(new java.awt.Point(audioFullSampleStartReadingFromIndex, intersectPointEndIndex));
						audioFullSampleStartReadingFromIndex = intersectPointEndIndex;
						down = false;
					}
				}
			}

			//This is cancelled due to the fact that we will still going to be keep receive data, thus keep trimming the edge is
			//not correct but instead should wait and see if the new coming data has been pattern end point.
			//Thus do nothing here.
//			//Join the tail if the last signal doesn't reach the absolute end.
//			if (audioFullSampleStartReadingFromIndex != fullAudioData.length - 1) {
//				groupedSignal.add(new java.awt.Point(audioFullSampleStartReadingFromIndex, fullAudioData.length - 1));
//			}

			//TODO: Bruteforce ICL in the future to identify pattern that were recognized before but not presented via expectation
			//(via pattern feedback).

			//Filter out already recognized region and recognize segmented audio as new stray pattern if they are not recognized yet.
			ArrayList<java.awt.Point> finalStraySignalGroup = new ArrayList<java.awt.Point>();
			for (int i=0; i<groupedSignal.size(); i++) {
				//If the matching rate is below 25%, mark it as a stray.
				int startIndex = groupedSignal.get(i).x;
				int endIndex = groupedSignal.get(i).y;

				int matchedCount = 0;
				//+1 to convert it from index to size.
				for (int matchIndex=startIndex; matchIndex<endIndex+1; matchIndex++) {
					if (audioProcessedIndex.get(matchIndex) != 0)
						matchedCount++;
				}
				double matchedPercentage = ( (double)matchedCount / (double)(endIndex - startIndex) ) * 100d;

				if (matchedPercentage < 25d) {
					finalStraySignalGroup.add(groupedSignal.get(i));

					//Mark those regions as processed.
					for (int matchIndex=startIndex; matchIndex<endIndex; matchIndex++) {
						audioProcessedIndex.set(i, audioProcessedIndex.get(i) + 1);
					}
				}
			}

			//Seek for any stray pattern that lurks in between segments (overlapping) that missed the stray pattern matching
			//capture above, ignoring any data after last grouped signal end as they are to be appended with new incoming data.
			int startIndex = 0;
			//Minimum pattern size, will skip it if smaller than it.
			int minimumPatternSize = 10;
			boolean patternStarted = false;
			//Must check else it may throw out of bound exception during grouped signal access.
			if (!groupedSignal.isEmpty()) {
				for (int i=0; i<groupedSignal.get(groupedSignal.size()-1).y; i++) {
					//0 means never processed before.
					if (audioProcessedIndex.get(i) == 0) {
						if (!patternStarted) {
							patternStarted = true;
							startIndex = i;
						}
						//If pattern already started, do nothing until we meet the break point (a non 0 value).
					}
					else {
						if (patternStarted) {
							patternStarted = false;
							//Only register it if its size is larger than the permitted minimum size.
							if (i - startIndex > minimumPatternSize) {
								finalStraySignalGroup.add(new java.awt.Point(startIndex, i));
							}
						}
						//If pattern not started and the data is already processed before (non 0), ignore it.
					}
				}
			}
			//TODO: Should you capture the last pattern that may be started but not ended due out of bound? Currently no.

			for (int i=0; i<finalStraySignalGroup.size(); i++) {
				//+1 as it is not inclusive by default.
				double[] dataForThisSignal = Arrays.copyOfRange(fullAudioData, finalStraySignalGroup.get(i).x, finalStraySignalGroup.get(i).y + 1);
				byte[] binaryDataRepresentation = Util.audioDoubleArrayToByteArray(dataForThisSignal);

				Vertex patternGeneralVertex = null;

				//Commit retry model.
				boolean txError = true;
				int txRetried = 0;
				while (txError) {
					if (txRetried > dbErrMaxRetryCount) {
						throw new IllegalStateException("Failed to complete transaction after number of retry:"
								+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
					}
					else if (txError) {
						if (txRetried != 0)
							Util.sleep(dbErrRetrySleepTime);
						txRetried++;
					}
					txGraph.begin();

					generalVertex = Util.vReload(generalVertex, txGraph);

					//Create 2 vertex to store the result, as it doens't match any given pattern, it is not entitled to have an
					//'occurrence' edge toward any other pattern as he is new and stray without origin.
					patternGeneralVertex = txGraph.addVertex(DBCN.V.general.rawDataICL.audio.cn
							, DBCN.V.general.rawDataICL.audio.cn);
					Vertex patternDataVertex = txGraph.addVertex(DBCN.V.LTM.rawDataICL.audio.cn, DBCN.V.LTM.rawDataICL.audio.cn);

					patternDataVertex.setProperty(LP.data, binaryDataRepresentation);
					//For generated pattern, just use current time.
					patternDataVertex.setProperty(LP.audioAbsTimestamp, System.currentTimeMillis());
					patternDataVertex.addEdge(DBCN.E.data, patternGeneralVertex);

					//the vertex that this vertex origin from is the raw data vertex that contains the raw data.
					patternGeneralVertex.addEdge(DBCN.E.parent, generalVertex);

					txError = txGraph.finalizeTask(true);
				}

				//Start a new transaction to avoid retry induced data inconsistency at GCA site. To guarantee idempotent.
				txGraph.begin();
				//TODO: should be the polyVal of the dist, not the globalDist, but uses that for simplicity.
				patternGeneralVertex.setProperty(LP.polyVal, globalDist);
				STMClient.addDist((double) patternGeneralVertex.getProperty(LP.polyVal), DBCN.V.general.rawDataICL.audio.cn, txGraph);

				//Add general vertex to GCA-able space.
				STMClient.rawDataICLAddToGCAQueue(patternGeneralVertex, txGraph);
				txGraph.finalizeTask();

				if (loggerSet)
					logger.log(logCredential, LVL.INFO, CLA.NORM,
						"RawDataICL audio stray pattern registered. Size: " + binaryDataRepresentation.length);
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("File just generated but gone missing, did you removed it manully during runtime? "
					+ "Filename is: " + concatenatedOutputFilePath);
		}
	}

	public void execute(Vertex generalVertex, Graph txGraph, double globalVariance, double globalDist) {
		//-generalVertex here is raw input data' general vertex. Identify it and create a new occurrence vertex
		//for already existing pattern, and a new 'vertexType' named vertex to store new pattern, they all treat this current vertex
		//as their parent vertex.
		String vertexClass = generalVertex.getCName();

		//TODO: IMPORTANT: make sure this function can be terminated in timely fashion if new frame arrive. New frame arrival means
		//you must switch to that frame to process it immediately. You can make breakpoint at each pattern seek, or just skip it at once
		//as what all we can see in the end is the purges that arrives at STM. Now we pretend we can finish before new arrival.

		/*
		 * NOTE: IF you are upgrading this in the future, here are the missing features:
		 * globalAllowance, the global variance. Used to calculated maximum allowed threshold.
		 * NON ROI spot calculation by excluding already calculated coordinate, and its pattern area coverage. Focus on the least
		 * computed or medium depending on DM, which is seek unique when 50% around. Seek familiar at 25-- or 75++.
		 *
		 * Concept NOTE: we will not allow multiple pass, just one pass for each frame, therefore all these operation are to be done
		 * by one crawler, and the internal comparison task are allowed to further parallelize, as long as it cant be seen from the
		 * outside.
		 */

		if (Util.equalAny(vertexClass, LTM.VISUAL_RAW)) {
			visualICLTxL(generalVertex, globalDist, globalVariance, txGraph);
		}

		else if (Util.equalAny(vertexClass, LTM.AUDIO_RAW)) {
			audioICLTxL(generalVertex, globalDist, txGraph);
		}

		else
			throw new IllegalStateException("Unknown and unsupported vertex class: " + vertexClass + "; RID:"
					+ generalVertex.getRid() + " during rawDataICL.");
	}
}
