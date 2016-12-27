package ICL;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.CvException;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
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
import org.opencv.features2d.Features2d;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
/**
 * Test the core logic of ICL only, without interfering with DB. Will be ported and replace the data input sorce from hardcoded
 * filenames into the DB address specified in the DB hierarchy standard.
 */
public class VisualICLTest {
	@Test
	public void visualLogic_testDrive_success() {
		/*
		 * There is 2 operation here, inRangeContourCalculation(select attention point by generating template),
		 * and descriptor matching(use template to identify new object)
		 */
		///--First task.
		System.loadLibrary( Core.NATIVE_LIBRARY_NAME );
		String sourcePath = "resources/test/visualICL/sampleInput.jpg";
		Mat srcImgMat = Imgcodecs.imread(sourcePath);
		Mat processedMat = Imgcodecs.imread(sourcePath);
		//	        Mat srcImgMat = Imgcodecs.imread(sourcePath, Imgcodecs.CV_LOAD_IMAGE_GRAYSCALE);
		//	        cvtColor(srcImgMat, srcImgMat, IMgcodecs.);
		if (srcImgMat == null)
		{
			System.out.println("Failed to load image at " + sourcePath);
			return;
		}

		System.out.println("Loaded image at " + sourcePath);

		//http://stackoverflow.com/questions/18581633/fill-in-and-detect-contour-rectangles-in-java-opencv;
		Core.inRange(srcImgMat, new Scalar(0, 144, 144), new Scalar(255,255,255), processedMat);
		Mat erode = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3,3));
		Imgproc.erode(processedMat, processedMat, erode);
		Imgproc.dilate(processedMat, processedMat, erode);
		Imgproc.dilate(processedMat, processedMat, erode);

		List<MatOfPoint> contours = new ArrayList<>();

		Imgproc.findContours(processedMat, contours, new Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
		Imgproc.drawContours(processedMat, contours, -1, new Scalar(255,255,0));

		for (int i=0; i<contours.size(); i++) {
			// Get bounding rect of contour  then draw the rect on the image.
			//http://docs.opencv.org/2.4/doc/tutorials/introduction/desktop_java/java_dev_intro.html
			Rect rect = Imgproc.boundingRect(contours.get(i));
			Imgproc.rectangle(processedMat, new Point(rect.x, rect.y), new Point(rect.x + rect.width, rect.y + rect.height), new Scalar(255, 255, 0));
			//	            Imgproc.floodFill(image, mask, seedPoint, newVal);

			//http://answers.opencv.org/question/29260/how-to-save-a-rectangular-roi/
			//Extract the rectangle area from the original image which has color, instead of the processedMat, which is grayscale.
			Mat outputRect = new Mat(srcImgMat, rect);
			Imgcodecs.imwrite("resources/test/visualICL/temp/ICLOutput/sam" + i + ".jpg", outputRect);
		}

		//----Second task.
		//Attempt to match the extracted data against frame.
		//http://stackoverflow.com/questions/28759253/how-to-crop-the-internal-area-of-a-contour

		//http://rkdasari.com/2013/11/09/homography-between-images-using-opencv-for-android/
		//Detect once and object and once for screen, then compare their pattern in a scale invariant way. BRISK is scale invariant.
		MatOfKeyPoint matOfKeyPointsOriginal = new MatOfKeyPoint();
		MatOfKeyPoint matOfKeyPointsTemplate = new MatOfKeyPoint();
		Mat template = Imgcodecs.imread("resources/test/visualICL/sampleInputScaledRotated.jpg");
		FeatureDetector brisk = FeatureDetector.create(FeatureDetector.BRISK);
		brisk.detect(srcImgMat, matOfKeyPointsOriginal);
		brisk.detect(template, matOfKeyPointsTemplate);

		DescriptorExtractor extractor = DescriptorExtractor.create(DescriptorExtractor.BRISK);
		Mat descriptorOriginal = new Mat();
		Mat descriptorTemplate = new Mat();
		extractor.compute(srcImgMat, matOfKeyPointsOriginal, descriptorOriginal);
		extractor.compute(template, matOfKeyPointsTemplate, descriptorTemplate);

		DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);
		MatOfDMatch descriptorMatches = new MatOfDMatch();
		matcher.match(descriptorOriginal, descriptorTemplate, descriptorMatches);

		//Seek the best match by discarding some bad matches based on their relative distance against all other points.
		double max_dist = 0; double min_dist = 100;
		List<DMatch> matchesList = descriptorMatches.toList();

		//Quick calculation of max and min distances between keypoints
		for (int i = 0; i < descriptorOriginal.rows(); i++ ) {
			Double dist = (double) matchesList.get(i).distance;
			if( dist < min_dist ) min_dist = dist;
			if( dist > max_dist ) max_dist = dist;
		}

		LinkedList<DMatch> good_matches = new LinkedList<DMatch>();

		for (int i = 0; i < descriptorOriginal.rows(); i++) {
			if(matchesList.get(i).distance < min_dist * 3){
				good_matches.addLast(matchesList.get(i));
			}
		}

		MatOfDMatch gm = new MatOfDMatch();
		gm.fromList(good_matches);

		//http://stackoverflow.com/questions/12937490/how-to-access-points-location-on-opencv-matcher
		System.out.println("GoodMatch Count:" + gm.size());
		List<KeyPoint> keypoints_objectList = matOfKeyPointsOriginal.toList();
		LinkedList<Point> matchedDescriptorPoint = new LinkedList<Point>();
		for (int i=0; i<good_matches.size(); i++) {
			matchedDescriptorPoint.addLast(keypoints_objectList.get(good_matches.get(i).queryIdx).pt);
		}

		//Create a bounding box around the selected points.
		MatOfPoint points = new MatOfPoint();
		points.fromList(matchedDescriptorPoint);
		Rect ROIRect = Imgproc.boundingRect(points);

		Mat outImg = new Mat();
		//Draw the matches to the outImg, then draw a rectangle
		Features2d.drawMatches(srcImgMat, matOfKeyPointsOriginal, template, matOfKeyPointsTemplate, gm, outImg);
		Imgproc.rectangle(outImg, new Point(ROIRect.x, ROIRect.y), new Point(ROIRect.x + ROIRect.width, ROIRect.y + ROIRect.height), new Scalar(255, 255, 255));
		//	        Features2d.drawKeypoints(srcImgMat, matOfKeyPoints, outImg);
		Imgcodecs.imwrite("resources/test/visualICL/temp/inRangeContourBounded.jpg", processedMat);
		Imgcodecs.imwrite("resources/test/visualICL/temp/matchingTemplate.jpg", outImg);

		//THUS super bug, he might recognize things wrongly but be never aware of it.
		//Calculate relevancy between the template and the extracted, identified image.
		//If they doesn't match at all, means either we have multiple same item,
		//We will only seek the unprocessed ROI for every point.
		//Give him the scale then he will know that he had miscalculated it.
		Scalar relevance = Core.mean(new Mat(srcImgMat, ROIRect));
		Scalar relevanceT = Core.mean(template);
		System.out.println("Mean:" + relevance.val[0]);
		System.out.println("Mean:" + relevance.val[1]);
		System.out.println("Mean:" + relevance.val[2]);
		System.out.println("Mean:" + relevance.val[3]);

		System.out.println("MeanT:" + relevanceT.val[0]);
		System.out.println("MeanT:" + relevanceT.val[1]);
		System.out.println("MeanT:" + relevanceT.val[2]);
		System.out.println("MeanT:" + relevanceT.val[3]);
	}

	@Test
	public void visualLogic_patternICLTestExpectError_success() {
		/*
		 * There is 2 operation here, inRangeContourCalculation(select attention point by generating template),
		 * and descriptor matching(use template to identify new object)
		 */
		///--First task.
		//We found that any image smaller than 6x6 will raise an assertion error:
		//OpenCV Error: Assertion failed (dsize.area() > 0 || (inv_scale_x > 0 && inv_scale_y > 0)) in resize, file /home/xeivnagn/CodeBlockProjectFile/ISRARebirth/thirdPartyTAR/opencv-3.0.0/modules/imgproc/src/imgwarp.cpp, line 3209
		System.loadLibrary( Core.NATIVE_LIBRARY_NAME );
		boolean error = false;

		try {
			String sourcePath = "resources/test/visualICL/5x5.jpg";
			Mat template = Imgcodecs.imread(sourcePath);

			FeatureDetector brisk = FeatureDetector.create(FeatureDetector.BRISK);
			MatOfKeyPoint matOfKeyPointsTemplate = new MatOfKeyPoint();
			brisk.detect(template, matOfKeyPointsTemplate);
		}
		catch (CvException e) {
			error = true;
		}
		assert error;
	}
}
