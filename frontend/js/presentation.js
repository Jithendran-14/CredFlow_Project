// Display values without changing stored amounts or submission delivery states.
export function formatMoney(value) {
 if(value===undefined||value===null)return '\u2014';
 const text=String(value);if(!/^-?\d+(\.\d{1,2})?$/.test(text))return '\u2014';
 const [whole,fraction='00']=text.replace('-','').split('.');
 return `${text.startsWith('-')?'-':''}\u20b9${BigInt(whole).toLocaleString('en-IN')}.${fraction.padEnd(2,'0')}`;
}
export async function withApplicationStatuses(rows,readApplication) {
 return Promise.all(rows.map(async row=>{
  if(!row.applicationId)return {...row};
  try {
   const application=await readApplication(row.applicationId);
   if(application.applicationId!==row.applicationId || typeof application.status!=='string' || !application.status)throw Error('Invalid application status');
   return {...row,applicationStatus:application.status};
  }catch{return {...row,applicationStatus:'UNAVAILABLE'};}
 }));
}

// Read real lender offers in one request for the displayed page. Never substitute the requested amount.
export async function withOfferedAmounts(rows,readAmounts){
 const ids=[...new Set(rows.map(r=>r.applicationId).filter(Boolean))];if(!ids.length)return rows.map(r=>({...r,offeredAmount:null}));
 try{const data=await readAmounts(ids);if(!Array.isArray(data.items))throw Error('Invalid amount response');const byId=new Map(data.items.map(r=>[r.applicationId,r]));return rows.map(r=>({...r,offeredAmount:byId.get(r.applicationId)?.offeredAmount??null,offerStatus:byId.get(r.applicationId)?.offerStatus??null,amountUnavailable:false}));}
 catch{return rows.map(r=>({...r,offeredAmount:null,amountUnavailable:!!r.applicationId}));}
}
