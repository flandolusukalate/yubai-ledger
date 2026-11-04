package cn.yubai.ledger;

import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** ML Kit block iteration order is not reading order. Reconstruct visible rows. */
final class ScreenOcrText {
    private static final class Row {
        final List<Text.Line> lines=new ArrayList<>();float center,height;
        Row(Text.Line line){Rect r=line.getBoundingBox();center=r.exactCenterY();height=r.height();lines.add(line);}
    }
    static String read(Text result){
        List<Text.Line> lines=new ArrayList<>();
        for(Text.TextBlock block:result.getTextBlocks())for(Text.Line line:block.getLines())if(line.getBoundingBox()!=null)lines.add(line);
        lines.sort(Comparator.comparingDouble(line->line.getBoundingBox().exactCenterY()));
        List<Row> rows=new ArrayList<>();
        for(Text.Line line:lines){
            Rect r=line.getBoundingBox();Row row=rows.isEmpty()?null:rows.get(rows.size()-1);
            if(row==null||Math.abs(r.exactCenterY()-row.center)>Math.max(r.height(),row.height)*0.4f)rows.add(new Row(line));
            else{row.center=(row.center*row.lines.size()+r.exactCenterY())/(row.lines.size()+1);row.height=Math.max(row.height,r.height());row.lines.add(line);}
        }
        List<String> ordered=new ArrayList<>();
        for(Row row:rows){row.lines.sort(Comparator.comparingInt(line->line.getBoundingBox().left));for(Text.Line line:row.lines)ordered.add(line.getText());}
        return String.join("\n",ordered);
    }
}
