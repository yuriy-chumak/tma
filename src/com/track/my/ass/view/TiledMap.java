package com.track.my.ass.view;

import java.util.concurrent.atomic.AtomicInteger;

import com.iaaa.*;
import com.iaaa.gps.nmea.GSA;
import com.iaaa.gps.nmea.RMC;
import com.track.my.ass.Preferences;

import android.content.Context;
import android.content.SharedPreferences.Editor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Paint.Style;
import android.util.AttributeSet;
import android.util.FloatMath;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;



//	http://upload.wikimedia.org/wikipedia/commons/6/62/Latitude_and_Longitude_of_the_Earth.svg
public class TiledMap extends View
//	implements Saveable
{
	public void Update()
	{
		dirty = true;
		post(new Runnable() {
			public void run() {
				invalidate();
			}
		});
	}
	public void SyncedUpdate()
	{
		dirty = true;
		invalidate();
	}
	
	TextView infoline = null;
	public void setInfoline(TextView infoline)
	{
		this.infoline = infoline;
	}
	
	public Subscriber OnTileUpdate = new Subscriber() {
		@Override
		public void Knock() {
			post(new Runnable() {
				public void run() {
					invalidate();
				}
			});
		}
	};

	boolean dirty = true;
	public Subscriber OnGPSChange = new Subscriber() {
		@Override
		public void Knock() {
			if (dirty)
				return;
			if (RMC.Status != 'A')
				return;
			if (Latitude == RMC.Latitude &&
				Longitude == RMC.Longitude &&
				Course == RMC.Course)
				return;
			Latitude = RMC.Latitude;
			Longitude = RMC.Longitude;
			Course = RMC.Course;

			Update();
		}
	};
	
	
	float Latitude = 0;
	float Longitude = 0;
	float Course = 0;
	
//	Bitmap plusBitmap = BitmapFactory.decodeResource(Program.getContext().getResources(), R.drawable.plus);	
//	Bitmap minusBitmap = BitmapFactory.decodeResource(Program.getContext().getResources(), R.drawable.minus);

	public static final int TILESIZE = 256;
	
	public static final int BLOCK_SIZE = 256;	// Размер тайла
	public static final int SCALE_LIMIT = 18;	// Не более 18! (всего масштабов, значит, 19)

	// Масштаб считаем с 0-го! Но юзеру говорим, что с 1го :)
	public static final int NumTiles[]    = new int[SCALE_LIMIT + 1];
	public static final int BitmapSize[]  = new int[SCALE_LIMIT + 1];
	public static final int BitmapOrigo[] = new int[SCALE_LIMIT + 1];
	
	public static final float PixelsPerLonDegree[] = new float[SCALE_LIMIT + 1];
	public static final float PixelsPerLonRadian[] = new float[SCALE_LIMIT + 1];
	
	public static float Lat(int y, int Scale)
	{
		float dy = Math.abs(BitmapOrigo[Scale] - y);
		
		float fi = 2.116374027980f * dy / BitmapOrigo[Scale];
		return fi;
	}
	
	static {
		for (int i = 0; i <= SCALE_LIMIT; i++) {
			final int nt = NumTiles[i]   = 1 << i;
			final int bs = BitmapSize[i] = nt * BLOCK_SIZE;
			BitmapOrigo[i] = bs / 2;
			
			PixelsPerLonDegree[i] = bs / 360.0f;
			PixelsPerLonRadian[i] = bs / (2 * (float)Math.PI);
		}
	};

	public static double atanh(double x) {
		return 0.5 * Math.log((1 + x) / (1 - x));
	}

	public static Point ToPosition(float Lon, float Lat, int Scale)
	{
		Point pt = new Point();
		ToPosition(pt, Lon, Lat, Scale);
		return pt;
	}
	public static void ToPosition(Point position, float Lon, float Lat, int Scale)
	{
		float x = (float)(Lon * PixelsPerLonDegree[Scale]);
		position.x = BitmapOrigo[Scale] + (int)x;
		
		double lat = Math.toRadians(Lat);
		switch (1) { // Tileset.projectionStyle1
		case 1: {
		    double numberOfTiles = Math.pow(2, Scale);
		    // LonToX
		    double x2 = (Lon+180) * (numberOfTiles*TILESIZE)/360.;
		    // LatToY
		    double projection = Math.log(Math.tan(Math.PI/4+lat/2));
		    double y = 1 - (projection / Math.PI);
		    y = y/2  * (numberOfTiles * TILESIZE);
		    
		    position.y = (int)y;
			break;
		}
		case 2: {
			double PixelsAtZoom = TILESIZE * Math.pow(2, Scale);
			double exct = 0.0818197;
			double z = Math.sin(lat);
			double c = PixelsAtZoom / (2*Math.PI);
			double x2 = Math.floor(PixelsAtZoom/2+Lon*(PixelsAtZoom/360));
			double y = Math.floor(PixelsAtZoom/2-c*(atanh(z)-exct*atanh(exct*z)));
			
			position.y = (int)y;
			break;
		}
		default:
			position.x = BitmapOrigo[Scale];
			position.y = BitmapOrigo[Scale];
			break;
		}
		
//		position.y = BitmapOrigo[Scale] - (int)(y * PixelsPerLonRadian[Scale]);
	}

	static final float RADIANS_PER_DEGREE = (float)(2.0 * Math.PI / 360.0);
	static final float LOGdiv2 = -0.6931471805599453094f;						// ln(0.5) constant

	public static float ToRadians(float degree)
	{
		return degree * RADIANS_PER_DEGREE;
	}

	// =======================================================================
	public TiledMap(Context context, AttributeSet attrs) {
		super(context, attrs);
	}
	
	public void toscale(int nz)
	{
		while (z < nz) {
			z++;
			position.x *= 2;
			position.y *= 2;
		}
		while (z > nz) {
			z--;
			position.x /= 2;
			position.y /= 2;
		}
		updatePositions();
		invalidate();
	}
	public void rescale(int dz)
	{
		if (dz > 0) {
			if (z < SCALE_LIMIT) {
				z++;		// fire "plus" event
				position.x *= 2;
				position.y *= 2;
			}
		}
		if (dz < 0) {
			if (z > 0) {
				z--;		// fire "minus" event
				position.x /= 2;
				position.y /= 2;
			}
		}
		updatePositions();
		invalidate();
	}
	public int getScale()
	{
		return z;
	}

	protected static long Timestamp = 0;
	protected static int z = Preferences.getInt("trackView.scale", 0);
	protected static Point position = Preferences.getPoint("trackView.position",
			ToPosition(0, 0, z)
//			math.ToPosition(24.01852f, 49.84312f, z)		// Широта: N49°50'35.22", Довгота:E24°01'06.66"
	);
	public Point getPosition() { return position; }

	public void save(Editor preferences)
	{
		preferences.putInt("trackView.scale", z);
		preferences.putInt("trackView.position.x", position.x);
		preferences.putInt("trackView.position.y", position.y);
		preferences.putInt("trackView.trackPosition", trackPosition ? 1 : 0);
	}

	// http://www.zdnet.com/blog/burnette/how-to-use-multi-touch-in-android-2-part-3-understanding-touch-events/1775?tag=content;siu-container
	protected boolean trackPosition = Preferences.getInt("trackView.trackPosition", 1) == 1;
	public void TrackPosition(boolean value) { trackPosition = value; }
	
	protected void onGetPosition()
	{
		// (GPGSA.Type >= '2')
		if (RMC.Status == 'A')
			ToPosition(position, RMC.Longitude, RMC.Latitude, z);
	}

	public String textScale = "";
	int pixels = 0;
	
	public boolean Magnifier = false;
	
	static AtomicInteger ai = new AtomicInteger(0);
	Paint color = new Paint(Paint.ANTI_ALIAS_FLAG);
	Point pos = new Point();
	@SuppressWarnings("deprecation")
	@Override
	protected void onDraw(Canvas canvas)
	{
		dirty = false;
		
		final int W = this.getWidth();
		final int H = this.getHeight();

		// TEMP:
		canvas.drawColor(Color.TRANSPARENT);
		
		if (Magnifier) {
			canvas.save();
			//canvas.scale(1/2f, 1/2f, W / 2, H / 2);
			canvas.scale(2f, 2f, W / 2, H / 2);
		}
		
		final int BLOCK_SIZE = 256;
		
		int M = Math.max(W, H);
		int cx = W / 2;
		int cy = H / 2;

		if (trackPosition)
			onGetPosition();
		
		int px = position.x, x = px / BLOCK_SIZE;
		int py = position.y, y = py / BLOCK_SIZE;
		
		final int B = BLOCK_SIZE;
		int X = W / 2 - px % BLOCK_SIZE;	// найдем границу кванта
		int Y = H / 2 - py % BLOCK_SIZE;	// и нарисуем главную (центральную) картинку

		ToPosition(pos, RMC.Longitude, RMC.Latitude, z);
		pos.x -= px;
		pos.y -= py;
		// Алгоритм спирального рисования. В начале цикла рисуем один вверх,
		//	в конце цикла - n-1 вверх, и если еще не конец, то все сначала.
		try {
//			Tile tile;
//
//			final int c = (int)((M + (BLOCK_SIZE - 1)) / BLOCK_SIZE + 1) | 1;
//			Tileset.Prepare(c * c);
//			
//	//		Draw: g.DrawImage(image, lx, ly);
//			Cache.get(x, y, z).Draw(canvas, X, Y);	// Центральная картинка
//			
//			for (int i = 1; i < c - 1; i++)
//			{	// на самом деле тут i += 2, так как чуть ниже тоже есть "++i"
//				Y -= BLOCK_SIZE;
//				tile = Cache.get(x, --y, z);	// отрисуем один элемент вверх
//				if (Y > -B)	// <оптимизированное условие видимости
//					tile.Draw(canvas, X, Y);
//					
//				// линия влево (та, что вверху)
//				for (int j = 0; j < i; j++) {
//					X -= BLOCK_SIZE;
//					tile = Cache.get(--x, y, z);	// кешируем
//					if (Y > -B && X > -B)
//						tile.Draw(canvas, X, Y);
//				}
//					
//				++i;
//	
//				// линия вниз (та, что слева)
//				for (int j = 0; j < i; j++) {
//					Y += BLOCK_SIZE;
//					tile = Cache.get(x, ++y, z);
//					if (X > -B && Y > -B && Y < H)
//						tile.Draw(canvas, X, Y);
//				}
//				// линия вправо (та, что внизу)
//				for (int j = 0; j < i; j++) {
//					X += BLOCK_SIZE;
//					tile = Cache.get(++x, y, z);
//					if (Y < H && X > -B && X < W)
//						tile.Draw(canvas, X, Y);
//				}
//	
//				// линия вверх (та, что справа)
//				for (int j = 0; j < i; j++) {
//					Y -= BLOCK_SIZE;
//					tile = Cache.get(x, --y, z);
//					if (X < W && Y < H && Y > -B)
//						tile.Draw(canvas, X, Y);
//				}
//			}
		} catch (Exception ex) {
			System.out.println(ex);
		}
		
//		if (GPRMC.Status == 0)
//			return;
		

		char type = GSA.Type; 
		double pdop = GSA.PDOP;

//		color.setColor(
//				(GPRMC.Status != 'A') ? 0x77808080 : 				
//				type < '2' ? 0x77808080 :	// no fix
//				pdop < 1.0 ? 0x7700FF00 :	// Green	ideal / идеально
//				pdop < 3.0 ? 0x77009900 :	// Greeny	excellent / превосходно
//				pdop < 6.0 ? 0x77FFFF00 :	// Yellow	good / хорошо
//				pdop < 8.0 ? 0x77FFA500 :	// Orange	moderate / может быть
//				pdop < 20. ? 0x77FF0000 :	// Red		fair / плохо
//				             0x77808080		// Gray		poor / ужасно
//		);
		color.setColor(
				(RMC.Status != 'A') ? 0x77000000 : 				
				type < '2' ? 0xaa606060 :	// no fix
				pdop < 3.0 ? 0xaaFF0000 :	// Red		excellent / превосходно
				pdop < 8.0 ? 0xaaFFA500 :	// Orange	moderate / может быть
				pdop < 20. ? 0xaaFFFF00 :	// Yellow	fair / плохо
				             0xaa808080		// Gray		poor / ужасно
		);
		
		color.setStyle(Style.STROKE);
		color.setStrokeWidth(2);
//		int r = (int)(pdop * 3);

		cx += pos.x;
		cy += pos.y;
		
		float a1 = ToRadians(RMC.Course);
		float a2 = ToRadians(RMC.Course + 120);
		float a3 = ToRadians(RMC.Course - 120);

		int a1x = (int)(Math.sin(a1) * 25); // legacy - FloatMath
		int a1y = (int)(Math.cos(a1) * 25);
		int a2x = (int)(Math.sin(a2) * 10);
		int a2y = (int)(Math.cos(a2) * 10);
		int a3x = (int)(Math.sin(a3) * 10);
		int a3y = (int)(Math.cos(a3) * 10);
		
		canvas.drawLine(cx + a1x, cy - a1y, cx + a2x, cy - a2y, color);
		canvas.drawLine(cx + a1x, cy - a1y, cx + a3x, cy - a3y, color);
		canvas.drawLine(cx + a2x, cy - a2y, cx + a3x, cy - a3y, color);
		
//		color.setColor(0xFF000000); // black
		canvas.drawLine(W - 48, H - 5, W - 48 - pixels, H - 5, color);
		canvas.drawLine(W - 48, H - 5, W - 48, H - 8, color);
		canvas.drawLine(W - 48 - pixels, H - 5, W - 48 - pixels, H - 8, color);
		
		if (Magnifier)
			canvas.restore();
	}
	
//	Color Yellow = new Color(0x77FFFF00);
	
	// http://www.zdnet.com/blog/burnette/how-to-use-multi-touch-in-android-2-part-3-understanding-touch-events/1775?tag=content;siu-container
	PointF firstPosition = null;
	PointF savedPosition = null;
	public Point getSavedPosition() { return new Point((int)savedPosition.x, (int)savedPosition.y); }
	boolean isCapturing = false;
	boolean isPositionMoved = false; public boolean isPositionMoved() {
		return Math.abs(firstPosition.x - savedPosition.x) + Math.abs(firstPosition.y - savedPosition.y) > 10;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
//		dumpEvent(event);
		switch (event.getAction())
		{
		case MotionEvent.ACTION_DOWN:
			isCapturing = true;
			savedPosition = firstPosition = new PointF(event.getX(), event.getY());
			trackPosition = false;
			isPositionMoved = false;
			break;
		case MotionEvent.ACTION_UP:
			isCapturing = false;
			break;
		case MotionEvent.ACTION_MOVE:
			if (isCapturing) {
				PointF current = new PointF(event.getX(), event.getY());
				PointF move = new PointF(savedPosition.x - current.x, savedPosition.y - current.y);
				savedPosition = current;
				isPositionMoved = true;
			
				position.x += (int)move.x;
				position.y += (int)move.y;
				invalidate();
				updatePositions();
			}
			break;
		}
		return super.onTouchEvent(event);
	}
	
	public void updatePositions()
	{
		float lat = Lat(position.y, z);
		float len = (float)Math.cos(lat) * 40000 / (float)NumTiles[z];
		
//		pixels = (int)(256 * (
//			len > 10000 ? (10000 / len) :
//			len > 1000  ? (1000 / len) :
//			len > 100   ? (100 / len) :
//			len > 10    ? (10 / len) :
//			len > 1     ? (1 / len) :
//			len > 0.1f  ? (0.1f / len) :
//				          (0.01f / len)));
//		textScale = 
//			len > 10000 ? "10000 km" :
//			len > 1000  ? "1000 km" :
//			len > 100   ? "100 km" :
//			len > 10    ? "10 km" :
//			len > 1     ? "1 km" :
//			len > 0.1f  ? "100 m" :
//				          "10 m";
//			
//		if (infoline != null)
//			infoline.setText(textScale);
	}
}
