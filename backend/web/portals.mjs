// Portal identities are backend configuration; lender ownership is also enforced by Java services.
export const portals = Object.freeze([
 {id:'marketplace',name:'Marketplace',role:'MARKETPLACE',port:5174},
 {id:'seller',name:'Seller',role:'SELLER',port:5175},
 {id:'lender',name:'Lender',role:'LENDER_OFFICER',port:5176},
 {id:'admin',name:'Administrator',role:'ADMIN',port:5178},
]);
