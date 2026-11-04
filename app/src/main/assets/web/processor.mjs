import * as C from './core.mjs';

// No UI, animation, network, timer or independent accounting formula lives here.
try {
  let complete=false;
  for(let attempt=0;attempt<3;attempt++) {
    const input=JSON.parse(Processor.snapshot());
    if(input.error)throw new Error(input.error);
    if(!input.state||!input.queue.length){Processor.done('idle','');complete=true;break;}
    // Read the live snapshot. Candidate and purpose state survives process
    // recreation; backup restore is a separate, explicitly reviewed operation.
    const state=JSON.parse(input.state);
    if(state.version!==1||!Number.isSafeInteger(state.revision))throw new Error('账本格式异常，采集记录已保留');
    const next=C.reduce(state,{type:'INGEST',value:input.queue});
    const result=Processor.commit(state.revision,JSON.stringify(next),JSON.stringify(input.queue.map(c=>c.queueId)));
    if(result==='conflict')continue;
    if(result!=='ok')throw new Error(result);
    Processor.done('updated','');complete=true;break;
  }
  if(!complete)throw new Error('账本正在更新，采集记录已保留，稍后重试');
}catch(error){Processor.done('error',String(error.message||'自动处理失败，采集记录已保留').slice(0,180));}
