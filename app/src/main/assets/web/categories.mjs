export const CATEGORIES=Object.freeze({food:'餐饮',transport:'交通',fun:'娱乐',goods:'日用品',trip:'出游',fixed:'固定费用',other:'其他'});
const normalized=name=>name.normalize('NFKC').trim().toLocaleLowerCase();
export function categories(state,{activeOnly=false}={}){
 return {...CATEGORIES,...Object.fromEntries((state.categories||[]).filter(c=>!activeOnly||!c.archived).map(c=>[c.id,c.name]))};
}
export function validateCategories(list=[]){
 if(!Array.isArray(list)||list.length>64)throw new Error('最多保存64个自定义分类');
 const ids=new Set(),names=new Set(Object.values(CATEGORIES).map(normalized));
 for(const c of list){
  if(!c||Object.keys(c).some(k=>!['id','name','archived'].includes(k))||!/^cat-[A-Za-z0-9_-]{1,80}$/.test(c.id||'')||ids.has(c.id)||typeof c.name!=='string'||!c.name.trim()||c.name.length>24||/[\p{Cc}\p{Cf}]/u.test(c.name)||typeof c.archived!=='boolean')throw new Error('自定义分类格式无效，名称限1至24字');
  const name=normalized(c.name);if(names.has(name))throw new Error('已有同名分类，请使用现有分类或换一个名称');
  ids.add(c.id);names.add(name);
 }
}
export function saveCategory(state,value,id){
 const list=state.categories||[],old=list.find(c=>c.id===id);
 const next={id,name:String(value.name??old?.name??'').trim(),archived:value.archived??old?.archived??false};
 const updated=old?list.map(c=>c.id===id?next:c):[...list,next];validateCategories(updated);state.categories=updated;
}
export function categorySpending(state,transactions){
 const labels=categories(state),totals=new Map();
 for(const t of transactions){
  if(!['expense','refund'].includes(t.type))continue;
  for(const split of t.splits){
   const row=totals.get(split.category)||{id:split.category,name:labels[split.category],spent:0,refund:0,net:0};
   row[t.type==='expense'?'spent':'refund']+=split.amount;row.net=row.spent-row.refund;totals.set(split.category,row);
  }
 }
 return [...totals.values()].sort((a,b)=>b.net-a.net||a.name.localeCompare(b.name,'zh-CN'));
}
