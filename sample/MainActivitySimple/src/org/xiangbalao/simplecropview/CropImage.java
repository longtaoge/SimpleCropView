/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xiangbalao.simplecropview;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

import org.xiangbalao.simplecropimage.R;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.FaceDetector;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * The activity can crop specific region of interest from an image.
 */
public class CropImage extends MonitoredActivity {

	final int IMAGE_MAX_SIZE = 1024;
	private static final String TAG = "CropImage";
	public static final String IMAGE_PATH = "image-path";
	public static final String SCALE = "scale";
	public static final String ORIENTATION_IN_DEGREES = "orientation_in_degrees";
	public static final String ASPECT_X = "aspectX";
	public static final String ASPECT_Y = "aspectY";
	public static final String OUTPUT_X = "outputX";
	public static final String OUTPUT_Y = "outputY";
	public static final String SCALE_UP_IF_NEEDED = "scaleUpIfNeeded";
	public static final String CIRCLE_CROP = "circleCrop";
	public static final String RETURN_DATA = "return-data";
	public static final String RETURN_DATA_AS_BITMAP = "data";
	public static final String ACTION_INLINE_DATA = "inline-data";
	// These are various options can be specified in the intent.
	private Bitmap.CompressFormat mOutputFormat = Bitmap.CompressFormat.JPEG;
	private Uri mSaveUri = null;
	private boolean mDoFaceDetection = true;

	private final Handler mHandler = new Handler();

	private int mAspectX;
	private int mAspectY;

	private CropImageView mImageView;
	private ContentResolver mContentResolver;
	private Bitmap mBitmap;
	private String mImagePath;

	boolean mWaitingToPick; // Whether we are wait the user to pick a face.
	boolean mSaving; // Whether the "save" button is already clicked.

	// These options specifiy the output image size and whether we should
	// scale the output to fit it (or just crop it).
	@SuppressWarnings("unused")
	private boolean mScaleUp = true;

	@Override
	public void onCreate(Bundle icicle) {

		super.onCreate(icicle);
		mContentResolver = getContentResolver();

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.cropimage1);

		mImageView = (CropImageView) findViewById(R.id.image);
		mImageView.setCropMode(CropImageView.CropMode.RATIO_16_10);
		showStorageToast(this);

		Intent intent = getIntent();
		Bundle extras = intent.getExtras();
		if (extras != null) {

			if (extras.getString(CIRCLE_CROP) != null) {

				if (Build.VERSION.SDK_INT > Build.VERSION_CODES.HONEYCOMB) {
					mImageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
				}

				mAspectX = 1;
				mAspectY = 1;
			}

			mImagePath = extras.getString(IMAGE_PATH);

			mSaveUri = getImageUri(mImagePath);
			mBitmap = getBitmap(mImagePath);

			if (extras.containsKey(ASPECT_X)
					&& extras.get(ASPECT_X) instanceof Integer) {

				mAspectX = extras.getInt(ASPECT_X);
			} else {

				throw new IllegalArgumentException("aspect_x must be integer");
			}
			if (extras.containsKey(ASPECT_Y)
					&& extras.get(ASPECT_Y) instanceof Integer) {

				mAspectY = extras.getInt(ASPECT_Y);
			} else {

				throw new IllegalArgumentException("aspect_y must be integer");
			}

			mScaleUp = extras.getBoolean(SCALE_UP_IF_NEEDED, true);
		}

		if (mBitmap == null) {

			Log.d(TAG, "finish!!!");
			finish();
			return;
		}

		// Make UI fullscreen.
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

		findViewById(R.id.discard).setOnClickListener(
				new View.OnClickListener() {
					public void onClick(View v) {

						setResult(RESULT_CANCELED);
						finish();
					}
				});

		findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {

				try {
					onSaveClicked();
				} catch (Exception e) {
					finish();
				}
			}
		});
		findViewById(R.id.rotateLeft).setOnClickListener(
				new View.OnClickListener() {
					public void onClick(View v) {
						;
						mImageView.rotateImage(270);

						mRunFaceDetection.run();
					}
				});

		findViewById(R.id.rotateRight).setOnClickListener(
				new View.OnClickListener() {
					public void onClick(View v) {

						mImageView.rotateImage(90);
						mRunFaceDetection.run();
					}
				});
		startFaceDetection();
	}

	private Uri getImageUri(String path) {

		return Uri.fromFile(new File(path));
	}

	private Bitmap getBitmap(String path) {

		Uri uri = getImageUri(path);
		InputStream in = null;
		try {
			in = mContentResolver.openInputStream(uri);

			// Decode image size
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;

			BitmapFactory.decodeStream(in, null, o);
			in.close();

			int scale = 1;
			if (o.outHeight > IMAGE_MAX_SIZE || o.outWidth > IMAGE_MAX_SIZE) {
				scale = (int) Math.pow(
						2,
						(int) Math.round(Math.log(IMAGE_MAX_SIZE
								/ (double) Math.max(o.outHeight, o.outWidth))
								/ Math.log(0.5)));
			}

			BitmapFactory.Options o2 = new BitmapFactory.Options();
			o2.inSampleSize = scale;
			in = mContentResolver.openInputStream(uri);
			Bitmap b = BitmapFactory.decodeStream(in, null, o2);
			in.close();

			return b;
		} catch (FileNotFoundException e) {
			Log.e(TAG, "file " + path + " not found");
		} catch (IOException e) {
			Log.e(TAG, "file " + path + " not found");
		}
		return null;
	}

	private void startFaceDetection() {

		if (isFinishing()) {
			return;
		}

		mImageView.setImageBitmap(mBitmap);

		Util.startBackgroundJob(this, null, "请稍后\u2026", new Runnable() {
			public void run() {

				final CountDownLatch latch = new CountDownLatch(1);
				final Bitmap b = mBitmap;
				mHandler.post(new Runnable() {
					public void run() {

						if (b != mBitmap && b != null) {
							mImageView.setImageBitmap(mBitmap);
							mBitmap.recycle();
							mBitmap = b;
						}

						latch.countDown();
					}
				});
				try {
					latch.await();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				mRunFaceDetection.run();
			}
		}, mHandler);
	}

	private void onSaveClicked() throws Exception {

		if (mSaving)
			return;
		mSaving = true;
		// Return the cropped image directly or save it to the specified URI.
		Bundle myExtras = getIntent().getExtras();
		if (myExtras != null
				&& (myExtras.getParcelable("data") != null || myExtras
						.getBoolean(RETURN_DATA))) {

			Bundle extras = new Bundle();
			extras.putParcelable(RETURN_DATA_AS_BITMAP,
					mImageView.getCroppedBitmap());
			setResult(RESULT_OK, (new Intent()).setAction(ACTION_INLINE_DATA)
					.putExtras(extras));
			finish();
		} else {
			final Bitmap b = mImageView.getCroppedBitmap();
			Util.startBackgroundJob(this, null,
					getString(R.string.saving_image), new Runnable() {
						public void run() {

							saveOutput(b);
						}
					}, mHandler);
		}
	}

	private void saveOutput(Bitmap croppedImage) {

		if (mSaveUri != null) {
			OutputStream outputStream = null;
			try {
				outputStream = mContentResolver.openOutputStream(mSaveUri);
				if (outputStream != null) {
					croppedImage.compress(mOutputFormat, 90, outputStream);
				}
			} catch (IOException ex) {

				Log.e(TAG, "Cannot open file: " + mSaveUri, ex);
				setResult(RESULT_CANCELED);
				finish();
				return;
			} finally {

				Util.closeSilently(outputStream);
			}

			Bundle extras = new Bundle();
			Intent intent = new Intent(mSaveUri.toString());
			intent.putExtras(extras);
			intent.putExtra(IMAGE_PATH, mImagePath);
			intent.putExtra(ORIENTATION_IN_DEGREES,
					Util.getOrientationInDegree(this));
			setResult(RESULT_OK, intent);
		} else {

			Log.e(TAG, "not defined image url");
		}
		croppedImage.recycle();
		finish();
	}

	@Override
	protected void onPause() {

		super.onPause();
		// BitmapManager.instance().cancelThreadDecoding(mDecodingThreads);
	}

	@Override
	protected void onDestroy() {

		super.onDestroy();

		if (mBitmap != null) {

			mBitmap.recycle();
		}
	}

	Runnable mRunFaceDetection = new Runnable() {
		float mScale = 1F;
		@SuppressWarnings("unused")
		Matrix mImageMatrix;
		FaceDetector.Face[] mFaces = new FaceDetector.Face[3];
		int mNumFaces;

		// For each face, we create a HightlightView for it.
		private void handleFace(FaceDetector.Face f) {

			PointF midPoint = new PointF();

			int r = ((int) (f.eyesDistance() * mScale)) * 2;
			f.getMidPoint(midPoint);
			midPoint.x *= mScale;
			midPoint.y *= mScale;

			int midX = (int) midPoint.x;
			int midY = (int) midPoint.y;

			int width = mBitmap.getWidth();
			int height = mBitmap.getHeight();

			Rect imageRect = new Rect(0, 0, width, height);

			RectF faceRect = new RectF(midX, midY, midX, midY);
			faceRect.inset(-r, -r);
			if (faceRect.left < 0) {
				faceRect.inset(-faceRect.left, -faceRect.left);
			}

			if (faceRect.top < 0) {
				faceRect.inset(-faceRect.top, -faceRect.top);
			}

			if (faceRect.right > imageRect.right) {
				faceRect.inset(faceRect.right - imageRect.right, faceRect.right
						- imageRect.right);
			}

			if (faceRect.bottom > imageRect.bottom) {
				faceRect.inset(faceRect.bottom - imageRect.bottom,
						faceRect.bottom - imageRect.bottom);
			}

		}

		// Create a default HightlightView if we found no face in the picture.
		private void makeDefault() {
			int width = mBitmap.getWidth();
			int height = mBitmap.getHeight();
			@SuppressWarnings("unused")
			Rect imageRect = new Rect(0, 0, width, height);
			// make the default size about 4/5 of the width or height
			int cropWidth = Math.min(width, height) * 4 / 5;
			int cropHeight = cropWidth;
			if (mAspectX != 0 && mAspectY != 0) {
				if (mAspectX > mAspectY) {
					cropHeight = cropWidth * mAspectY / mAspectX;
				} else {
					cropWidth = cropHeight * mAspectX / mAspectY;
				}
			}

			int x = (width - cropWidth) / 2;
			int y = (height - cropHeight) / 2;

			@SuppressWarnings("unused")
			RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);

		}

		// Scale the image down for faster face detection.
		private Bitmap prepareBitmap() {

			if (mBitmap == null) {

				return null;
			}

			// 256 pixels wide is enough.
			if (mBitmap.getWidth() > 256) {

				mScale = 256.0F / mBitmap.getWidth();
			}
			Matrix matrix = new Matrix();
			matrix.setScale(mScale, mScale);
			return Bitmap.createBitmap(mBitmap, 0, 0, mBitmap.getWidth(),
					mBitmap.getHeight(), matrix, true);
		}

		public void run() {

			mImageMatrix = mImageView.getImageMatrix();
			Bitmap faceBitmap = prepareBitmap();

			mScale = 1.0F / mScale;
			if (faceBitmap != null && mDoFaceDetection) {
				FaceDetector detector = new FaceDetector(faceBitmap.getWidth(),
						faceBitmap.getHeight(), mFaces.length);
				mNumFaces = detector.findFaces(faceBitmap, mFaces);
			}

			if (faceBitmap != null && faceBitmap != mBitmap) {
				faceBitmap.recycle();
			}

			mHandler.post(new Runnable() {
				public void run() {

					mWaitingToPick = mNumFaces > 1;
					if (mNumFaces > 0) {
						for (int i = 0; i < mNumFaces; i++) {
							handleFace(mFaces[i]);
						}
					} else {
						makeDefault();
					}
					mImageView.invalidate();

					if (mNumFaces > 1) {
						Toast.makeText(CropImage.this, "Multi face crop help",
								Toast.LENGTH_SHORT).show();
					}
				}
			});
		}
	};

	public static final int NO_STORAGE_ERROR = -1;
	public static final int CANNOT_STAT_ERROR = -2;

	public static void showStorageToast(Activity activity) {

		showStorageToast(activity, calculatePicturesRemaining(activity));
	}

	public static void showStorageToast(Activity activity, int remaining) {

		String noStorageText = null;

		if (remaining == NO_STORAGE_ERROR) {

			String state = Environment.getExternalStorageState();
			if (state.equals(Environment.MEDIA_CHECKING)) {

				noStorageText = activity.getString(R.string.preparing_card);
			} else {

				noStorageText = activity.getString(R.string.no_storage_card);
			}
		} else if (remaining < 1) {

			noStorageText = activity.getString(R.string.not_enough_space);
		}

		if (noStorageText != null) {

			Toast.makeText(activity, noStorageText, 5000).show();
		}
	}

	public static int calculatePicturesRemaining(Activity activity) {

		try {
			String storageDirectory = "";
			String state = Environment.getExternalStorageState();
			if (Environment.MEDIA_MOUNTED.equals(state)) {
				storageDirectory = Environment.getExternalStorageDirectory()
						.toString();
			} else {
				storageDirectory = activity.getFilesDir().toString();
			}
			StatFs stat = new StatFs(storageDirectory);
			float remaining = ((float) stat.getAvailableBlocks() * (float) stat
					.getBlockSize()) / 400000F;
			return (int) remaining;
			// }
		} catch (Exception ex) {
			// if we can't stat the filesystem then we don't know how many
			// pictures are remaining. it might be zero but just leave it
			// blank since we really don't know.
			return CANNOT_STAT_ERROR;
		}
	}

}
