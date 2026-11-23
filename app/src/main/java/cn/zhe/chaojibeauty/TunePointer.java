package cn.zhe.chaojibeauty;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Invoked only by the already-authorized adb shell, never by the SystemUI process. */
public final class TunePointer {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> type=Class.forName("android.hardware.input.InputManagerGlobal");
        Object input=type.getMethod("getInstance").invoke(null);
        Method send=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));
        long down=0;
        float x=0,y=0;
        try {
            for(String line;(line=reader.readLine())!=null;){
                String[] parts=line.trim().split(" ");
                if(parts.length!=3)continue;
                int action=Integer.parseInt(parts[0]);
                if(action<0 || action>3 || (down==0 && action!=0))continue;
                x=Float.parseFloat(parts[1]); y=Float.parseFloat(parts[2]);
                long now=SystemClock.uptimeMillis();
                if(action==0)down=now;
                if(!emit(input,send,down,now,action,x,y))throw new IllegalStateException("touch rejected");
                if(action==1 || action==3)down=0;
            }
        }finally{if(down!=0)emit(input,send,down,SystemClock.uptimeMillis(),3,x,y);}
    }
    private static boolean emit(Object input,Method send,long down,long now,int action,float x,float y)throws Exception{
        MotionEvent e=MotionEvent.obtain(down,now,action,x,y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{return Boolean.TRUE.equals(send.invoke(input,e,0));}finally{e.recycle();}
    }
}
