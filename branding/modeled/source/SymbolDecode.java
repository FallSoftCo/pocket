import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
/** Android-runtime decoding probe; no UI or renderer. File API probes the PNG/WebP nodpi payloads. */
public class SymbolDecode {
 public static void main(String[] args) {
  for(String file:args) {
   long[] elapsed=new long[6];int bytes=0,width=0,height=0;
   for(int i=0;i<elapsed.length;i++) {
    long start=System.nanoTime();Bitmap image=BitmapFactory.decodeFile(file);
    if(image==null)throw new IllegalStateException("Decode failed: "+file);
    elapsed[i]=(System.nanoTime()-start)/1000000;bytes=image.getAllocationByteCount();width=image.getWidth();height=image.getHeight();image.recycle();
   }
   System.out.println("{\"file\":\""+file+"\",\"width\":"+width+",\"height\":"+height+",\"decodedBytes\":"+bytes+",\"decodeMs\":"+java.util.Arrays.toString(elapsed)+"}");
  }
 }
}
