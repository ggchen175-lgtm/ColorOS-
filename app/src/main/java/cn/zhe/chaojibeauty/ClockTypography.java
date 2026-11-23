package cn.zhe.chaojibeauty;

import android.graphics.*;
import android.icu.util.ChineseCalendar;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.Locale;
import java.util.regex.Pattern;

/** Sets native clock content as one typographic run before the material shades its alpha mask. */
final class ClockTypography {
    private static final Pattern TIME = Pattern.compile("[0-9]{1,2}[:：][0-9]{2}");
    private static final Pattern LUNAR = Pattern.compile("[闰閏]?[正一二三四五六七八九十冬腊臘]+月[初十廿卅一二三四五六七八九]+");
    static final int DEFAULT_WEIGHT = 2;
    private static final int[] DIGIT_WEIGHTS={400,510,590,700};
    private static final int[] DATE_WEIGHTS={400,500,600,700};
    private static final Typeface[][] faces=new Typeface[2][4];
    private static int digitChoice=DEFAULT_WEIGHT, dateChoice=DEFAULT_WEIGHT;

    static int choice(int index) { return index>=0 && index<4 ? index : DEFAULT_WEIGHT; }

    static boolean setWeights(int time,int date) {
        time=choice(time);
        date=choice(date);
        if (time==digitChoice && date==dateChoice) return false;
        digitChoice=time; dateChoice=date;
        return true;
    }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect ink = new Rect();
    private String previous="", lastLunar="", alternate="";
    private final StringBuilder value = new StringBuilder();
    private float baseline, left;

    static Typeface face(boolean subtitle, int index) {
        index = choice(index);
        Typeface[] cache = faces[subtitle ? 1 : 0];
        if (cache[index] == null) {
            int weight = subtitle ? DATE_WEIGHTS[index] : DIGIT_WEIGHTS[index];
            cache[index] = Typeface.create(Typeface.DEFAULT, weight, false);
        }
        return cache[index];
    }

    private void collect(View v, boolean subtitle) {
        if (v.getVisibility()!=View.VISIBLE || v.getAlpha()==0f) return;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for (int i=0;i<g.getChildCount();i++) collect(g.getChildAt(i),subtitle);
        } else if (v instanceof TextView) {
            String part=((TextView)v).getText().toString().trim();
            if (subtitle && !part.isEmpty() && LUNAR.matcher(part).matches()) {
                if (!part.equals(lastLunar)) {
                    lastLunar=part;
                    int y=new ChineseCalendar().get(ChineseCalendar.YEAR)-1;
                    alternate=" · "+"甲乙丙丁戊己庚辛壬癸".charAt(y%10)+"子丑寅卯辰巳午未申酉戌亥".charAt(y%12)+"年"+part;
                }
                value.append(alternate);
            } else value.append(part);
        }
    }

    boolean layout(View piece, boolean subtitle, Rect bounds,int cardTop) {
        Locale locale=piece.getResources().getConfiguration().getLocales().get(0);
        if (subtitle && !locale.getLanguage().equals("zh")) return false;
        value.setLength(0);
        collect(piece,subtitle);
        String text=value.toString();
        if (text.isEmpty() || (!subtitle && !TIME.matcher(text).matches())) return false;
        previous=text.replace('：',':');
        int width=piece.getWidth();
        float points=width/440f;
        // Date uses the phone base size scaled to the available width. Keep one baseline across both calendars.
        paint.setTypeface(face(subtitle,subtitle?dateChoice:digitChoice));
        paint.setTextSize(subtitle ? width*(22f/440f) : 256f);
        if (!subtitle) {
            paint.getTextBounds("00:00",0,5,ink);
            paint.setTextSize(256f * (.214f*width)/Math.max(1,ink.height()));
        }
        paint.setTextSize(paint.getTextSize()*LiveTune.size(subtitle?"dateSize":"clockSize"));
        float textWidth=paint.measureText(previous);
        if (textWidth>.93f*width) paint.setTextSize(paint.getTextSize()*.93f*width/textWidth);
        paint.getTextBounds(previous,0,previous.length(),ink);
        left=(width-ink.width())*.5f-ink.left+LiveTune.dp(piece,subtitle?"dateX":"clockX");
        float center=bounds.exactCenterY();
        if (piece.getHeight()>width && cardTop>width*.36f && cardTop<piece.getHeight()*.65f) {
            // CoverSheetKit has a time-bottom + 13pt branch. Anchor to the first occupied native card.
            float timeBottom=cardTop-13f*points;
            float bottom=subtitle ? timeBottom-.214f*width-13f*points : timeBottom;
            center=bottom-ink.height()*.5f;
        }
        baseline=center-ink.exactCenterY()+LiveTune.dp(piece,subtitle?"dateY":"clockY");
        bounds.set((int)Math.floor(left+ink.left),(int)Math.floor(baseline+ink.top),
                (int)Math.ceil(left+ink.right),(int)Math.ceil(baseline+ink.bottom));
        return true;
    }

    void draw(Canvas c) { draw(c,Color.WHITE); }

    void draw(Canvas c,int color) { paint.setColor(color); c.drawText(previous,left,baseline,paint); }
}
