package cn.yubai.ledger;

import java.util.List;

/** Each node tree is parsed independently: partial pages must never be spliced together. */
final class PageReadPipeline {
    interface Reader { List<String> read() throws Exception; }
    interface Parser<T> { T parse(List<String> lines) throws Exception; }
    static <T> T read(Reader source,Reader root,Parser<T> parser) throws Exception {
        return read(List.of(source),root,parser);
    }
    static <T> T read(List<Reader> sources,Reader root,Parser<T> parser) throws Exception {
        for(Reader source:sources){
            List<String> lines;
            try{lines=source.read();}catch(Exception stale){continue;}
            T result=parser.parse(lines);
            if(result!=null)return result;
        }
        return parser.parse(root.read());
    }
}
