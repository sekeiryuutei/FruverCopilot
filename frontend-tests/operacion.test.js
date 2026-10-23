/* Prueba de la pantalla de operacion con un DOM falso minimo (sin dependencias). Uso: node frontend-tests/operacion.test.js */
process.on('unhandledRejection',e=>{console.log('UNHANDLED:',e&&e.stack||e);process.exit(1)});
const fs=require('fs'),vm=require('vm');
/* ---------- DOM falso mínimo ---------- */
class TextN{constructor(t){this.nodeType=3;this.textContent=String(t);this.parent=null}}
class El{
  constructor(tag){this.tag=tag;this.nodeType=1;this.kids=[];this.parent=null;this.attrs={};this.dataset=new Proxy({},{set(t,k,v){t[k]=String(v);return true}});this.className='';this.listeners={};this.value='';this.style={};this.disabled=false;this.id=''}
  get classList(){const s=this;return{add:c=>{if(!s.className.split(' ').includes(c))s.className=(s.className+' '+c).trim()},remove:c=>{s.className=s.className.split(' ').filter(x=>x&&x!==c).join(' ')},contains:c=>s.className.split(' ').includes(c)}}
  get children(){return this.kids.filter(k=>k.nodeType===1)}
  get firstElementChild(){return this.children[0]||null}
  get nextElementSibling(){const c=this.parent?this.parent.children:[];return c[c.indexOf(this)+1]||null}
  get offsetWidth(){return 0}
  get textContent(){return this.kids.map(k=>k.textContent).join('')}
  set textContent(v){this.kids=[];const t=new TextN(v);t.parent=this;this.kids.push(t)}
  setAttribute(k,v){this.attrs[k]=v;if(k==='value')this.value=v;if(k==='id')this.id=v}
  _detach(n){if(n.parent){const i=n.parent.kids.indexOf(n);if(i>=0)n.parent.kids.splice(i,1)}n.parent=this}
  append(...ns){for(let n of ns){if(typeof n==='string')n=new TextN(n);this._detach(n);this.kids.push(n)}}
  prepend(...ns){for(let n of ns){this._detach(n);this.kids.unshift(n)}}
  replaceChildren(...ns){this.kids.forEach(k=>k.parent=null);this.kids=[];this.append(...ns)}
  insertBefore(n,ref){this._detach(n);const i=ref?this.kids.indexOf(ref):this.kids.length;this.kids.splice(i<0?this.kids.length:i,0,n)}
  remove(){if(this.parent){this.parent.kids.splice(this.parent.kids.indexOf(this),1);this.parent=null}}
  contains(n){for(;n;n=n.parent)if(n===this)return true;return false}
  addEventListener(t,f){(this.listeners[t]??=[]).push(f)}
  dispatch(t,ev={}){const e={target:this,...ev};for(let n=this;n;n=n.parent){(n.listeners[t]||[]).forEach(f=>f(e));if(typeof n['on'+t]==='function')n['on'+t](e)}}
  focus(){doc.activeElement=this}select(){}
  all(){const out=[];const w=n=>n.kids.forEach(k=>{if(k.nodeType===1){out.push(k);w(k)}});w(this);return out}
  matches(s){return s[0]==='.'?this.className.split(' ').includes(s.slice(1)):s[0]==='#'?this.id===s.slice(1):this.tag===s}
  querySelectorAll(s){return this.all().filter(x=>x.matches(s))}
  querySelector(s){return this.querySelectorAll(s)[0]||null}
}
const body=new El('body');const doc={activeElement:null,cookie:'',body,
  createElement:t=>new El(t),createTextNode:t=>new TextN(t),
  querySelector:s=>body.querySelector(s),querySelectorAll:s=>body.querySelectorAll(s)};
for(const id of ['main','toast','cnt','dl','desktopNav','bnav','logoutBtn','installBanner','btnInstall','btnInstallClose','printerBadge']){const e=new El('div');e.id=id;body.append(e)}
/* ---------- datos ---------- */
const products=[{id:1,name:'Papa criolla',unit:'LB',active:true,prices:{RETAIL:3000,WHOLESALE:2610}},
  {id:2,name:'Aguacate Hass',unit:'UNIDAD',active:true,prices:{RETAIL:4500,WHOLESALE:3920}},
  {id:3,name:'Tomate chonto',unit:'LB',active:true,prices:{RETAIL:2500,WHOLESALE:2180}}];
const mkOrder=(id,version=1)=>({id,version,status:'REVIEW',conversationStatus:'READY_FOR_APPROVAL',reviewStatus:'GREEN',inputType:'TEXT',aiStatus:'OK',aiNote:null,
  suggestedReply:null,confirmationDraft:null,customer:{id:1,name:'Felipe',phoneMasked:'****0105',type:'RETAIL'},address:'Av 3N',notes:'',receivedAt:new Date(Date.now()-19*60000).toISOString(),
  messageText:'2 aguacates y 4 libras de papa',hasAudio:false,corrected:false,total:21000,complete:true,saleId:null,printStatus:null,
  items:[{id:101,productId:2,productName:'Aguacate Hass',mentioned:'aguacates',quantity:2,unit:'UNIDAD',manualPrice:null,aiSummary:'Aguacate Hass 2 UNIDAD'},
         {id:102,productId:1,productName:'Papa criolla',mentioned:'papa criolla',quantity:4,unit:'LB',manualPrice:null,aiSummary:'Papa criolla 4 LB'}]});
let serverOrders=[mkOrder(10005)];const puts=[],calls=[];
const ctx={document:doc,console,setTimeout,clearTimeout,setInterval:()=>0,scrollTo(){},confirm:()=>true,location:{},Intl,Date,JSON,Math,Object,Map,Set,Array,Promise,Error,Number,String,
  window:{innerWidth:1024,addEventListener:()=>{}},
  navigator:{serviceWorker:{register:()=>Promise.resolve()}},
  EventSource:class{addEventListener(){}},
  fetch:async(url,o={})=>{calls.push({url,method:o.method||'GET',body:o.body?JSON.parse(o.body):null});let data=null;
    if(url==='/api/me')data={username:'admin',role:'ADMIN'};else if(url.startsWith('/api/products')&&(o.method||'GET')==='GET')data=products;
    else if(url==='/api/printer/status')data={activePrinter:'OFICINA',schedule:'04:00 - 19:00 OFICINA',failedCount:0,pendingCount:0};
    else if(url==='/api/sales/reprint-all-pending')data={status:'enqueued',count:2};
    else if(url.includes('/reprint'))data={status:'PRINT_PENDING',saleId:501,orderId:10008};
    else if(url==='/api/orders')data=serverOrders;else if(o.method==='PUT'&&url.startsWith('/api/orders/')){puts.push(JSON.parse(o.body));data=mkOrder(10005,2)}
    return{status:200,ok:true,json:async()=>data}}};
vm.createContext(ctx);
const html=fs.readFileSync(require('path').join(__dirname,'..','backend','src','main','resources','static','index.html'),'utf8');
vm.runInContext(html.match(/<script>([\s\S]*)<\/script>/)[1],ctx);
const ev=s=>vm.runInContext(s,ctx),tick=()=>new Promise(r=>setTimeout(r,5));
let fails=0;const ok=(n,c,extra='')=>{if(!c)fails++;console.log((c?'OK   ':'FAIL ')+n+(c?'':'  '+extra))};
const main=doc.querySelector('#main');const cardOf=id=>main.children.find(c=>c.dataset.id===String(id));
const rows=c=>c.querySelector('tbody').kids;const btn=(c,t)=>c.querySelectorAll('button').find(b=>b.textContent===t);
(async()=>{
  for(let i=0;i<50&&!ev('S.orders.length');i++)await tick();
  let c=cardOf(10005);
  ok('la tarjeta tiene data-id (causa raíz: h() ignoraba dataset)',!!c&&c.dataset.id==='10005');
  ok('2 filas iniciales',rows(c).length===2);

  btn(c,'+ Producto').dispatch('click');
  ok('"+ Producto" agrega la fila AL INSTANTE (síncrono, sin esperar refresco)',rows(c).length===3);
  btn(c,'+ Producto').dispatch('click');
  ok('un segundo clic NO crea otra fila vacía',rows(c).length===3);
  ok('la fila vacía queda enfocada',doc.activeElement===rows(c)[2].kids[0].kids[0]);

  // elegir un producto que ya existe -> se suma
  let pn=rows(c)[2].kids[0].kids[0];pn.value='Aguacate Hass';pn.dispatch('change');
  ok('producto repetido: NO queda fila duplicada',rows(c).length===2);
  ok('producto repetido: cantidad 2 -> 3',String(rows(c)[0].kids[1].kids[0].value)==='3',rows(c)[0].kids[1].kids[0].value);
  ok('aviso al usuario',doc.querySelector('#toast').textContent.includes('ya estaba'),doc.querySelector('#toast').textContent);
  ok('subtotal de la fila actualizado ($13.500)',rows(c)[0].querySelector('.sub').textContent==='$13.500',rows(c)[0].querySelector('.sub').textContent);

  // repetido escribiendo una cantidad: suma esa cantidad
  btn(c,'+ Producto').dispatch('click');
  let r=rows(c)[2];r.kids[1].kids[0].value='5';r.kids[1].kids[0].dispatch('input');
  pn=r.kids[0].kids[0];pn.value='papa criolla';pn.dispatch('change');
  ok('repetido con cantidad propia: papa 4 + 5 = 9',rows(c).length===2&&String(rows(c)[1].kids[1].kids[0].value)==='9',rows(c)[1].kids[1].kids[0].value);

  // producto nuevo
  btn(c,'+ Producto').dispatch('click');
  r=rows(c)[2];pn=r.kids[0].kids[0];pn.value='Tomate chonto';pn.dispatch('change');
  r.kids[1].kids[0].value='2';r.kids[1].kids[0].dispatch('input');
  ok('producto nuevo: 3 filas',rows(c).length===3);
  ok('total = 13.500 + 27.000 + 5.000 = $45.500',c.querySelector('.total').textContent==='$45.500',c.querySelector('.total').textContent);

  // borrar por referencia (bug antiguo: índices desfasados)
  rows(c)[0].kids[5].kids[0].dispatch('click');   // borra aguacate
  rows(c)[0].kids[5].kids[0].dispatch('click');   // borra papa (ahora es la primera)
  const names=ev('S.draft[10005].items.map(i=>i.name)');
  ok('borrar 2 filas seguidas deja la correcta (Tomate)',rows(c).length===1&&names.length===1&&names[0]==='Tomate chonto',JSON.stringify(names));

  // SSE: recarga con el mismo estado de servidor y borrador abierto -> no se toca la tarjeta
  await ev('loadOrders()');
  ok('con edición en curso la tarjeta NO se redibuja',cardOf(10005)===c);
  ok('aviso "cambió el pedido" cuando el servidor sube de versión',(serverOrders=[mkOrder(10005,7)],await ev('loadOrders()'),!!c.querySelector('.stale')));
  ev('S.draft={}');  // descartar

  // reconciliación: tarjeta sin cambios conserva su elemento; cambia solo la que cambió
  serverOrders=[mkOrder(10005,7),mkOrder(10006,1)];await ev('loadOrders()');
  const a1=cardOf(10005),b1=cardOf(10006);
  serverOrders=[mkOrder(10005,7),mkOrder(10006,1)];await ev('loadOrders()');
  ok('sin cambios: mismo elemento DOM (sin parpadeo)',cardOf(10005)===a1&&cardOf(10006)===b1);
  serverOrders=[mkOrder(10005,7),{...mkOrder(10006,2)}];await ev('loadOrders()');
  ok('solo se redibuja la tarjeta que cambió',cardOf(10005)===a1&&cardOf(10006)!==b1);
  serverOrders=[mkOrder(10006,2)];await ev('loadOrders()');
  ok('un pedido que desaparece se quita',!cardOf(10005)&&!!cardOf(10006));


  // ================= VALORES INVÁLIDOS (negativos, cero, enormes, precios negativos) =================
  serverOrders=[mkOrder(10005,1)];ev('S.draft={}');await ev('loadOrders()');c=cardOf(10005);
  const q0=()=>rows(c)[0].kids[1].kids[0],pr0=()=>rows(c)[0].kids[3].kids[0],sub0=()=>rows(c)[0].querySelector('.sub').textContent;
  const err0=()=>rows(c)[0].kids[0].querySelector('.prob')?.textContent||'',tot=()=>c.querySelector('.total').textContent;
  const typeQ=v=>{q0().value=v;q0().dispatch('input')},typePr=v=>{pr0().value=v;pr0().dispatch('input')};
  const putsBefore=puts.length;
  typeQ('-9');   // el caso de la captura
  ok('cantidad -9: NO entra al estado (queda vacía)',ev('S.draft[10005].items[0].quantity')===null);
  ok('cantidad -9: mensaje de error visible',err0().includes('mayor que 0'),err0());
  ok('cantidad -9: campo marcado en rojo',q0().className.includes('bad'));
  ok('cantidad -9: subtotal "—" (no $-90.000)',sub0()==='—',sub0());
  ok('cantidad -9: el total NO resta (queda solo la papa: $12.000)',tot()==='$12.000',tot());
  let prevented=[];['-','+','e','E','5'].forEach(k=>q0().dispatch('keydown',{key:k,preventDefault(){prevented.push(k)}}));
  ok('teclear - + e E se bloquea (y el 5 no)',prevented.join('')==='-+eE',prevented.join(''));
  pr0().dispatch('keydown',{key:'-',preventDefault(){prevented.push('P-')}});ok('el signo - se bloquea también en precio',prevented.includes('P-'));
  btn(c,'Guardar').dispatch('click');await tick();await tick();
  ok('Guardar con cantidad negativa: NO envía nada al servidor',puts.length===putsBefore);
  ok('Guardar: explica qué corregir',doc.querySelector('#toast').textContent.startsWith('Corrige Aguacate Hass'),doc.querySelector('#toast').textContent);
  const callsBefore=calls.length;btn(c,'APROBAR E IMPRIMIR').dispatch('click');await tick();await tick();
  ok('APROBAR con cantidad negativa: NO llama a /approve',!calls.slice(callsBefore).some(x=>x.url.includes('/approve')));
  for(const bad of['0','-0.5','100001','1000000000']){typeQ(bad);ok(`cantidad ${bad}: rechazada`,q0().className.includes('bad')&&ev('S.draft[10005].items[0].quantity')===null&&sub0()==='—')}
  typeQ('');ok('campo vacío: sin error pero sin cantidad (se exige al guardar)',!q0().className.includes('bad')&&ev('S.draft[10005].items[0].quantity')===null);
  typeQ('1,5');ok('coma decimal "1,5" válida -> 1.5 y sin error',ev('S.draft[10005].items[0].quantity')===1.5&&!q0().className.includes('bad')&&err0()==='');
  ok('subtotal correcto 1,5 × $4.500 = $6.750',sub0()==='$6.750',sub0());
  typePr('-500');
  ok('precio -500: inválido, sin subtotal, en rojo',pr0().className.includes('bad')&&sub0()==='—'&&err0().includes('negativo'),err0());
  btn(c,'Guardar').dispatch('click');await tick();await tick();
  ok('Guardar con precio negativo: NO envía',puts.length===putsBefore&&doc.querySelector('#toast').textContent.includes('negativo'),doc.querySelector('#toast').textContent);
  typePr('0');ok('precio 0 es válido (obsequio)',!pr0().className.includes('bad'));
  typePr('3000');ok('precio manual 3000 -> subtotal $4.500',sub0()==='$4.500',sub0());
  typePr('');ok('precio vacío = vuelve al del catálogo',ev('S.draft[10005].items[0].manualPrice')===null&&sub0()==='$6.750');
  btn(c,'Guardar').dispatch('click');await tick();await tick();
  ok('con valores válidos SÍ se envía, con 1.5',puts.length===putsBefore+1&&puts[puts.length-1].items[0].quantity===1.5,JSON.stringify(puts[puts.length-1]));
  const pq=ev('parseQty'),pp=ev('parsePrice');
  ok('parseQty: casos límite',[['5',true,5],[' 2,25 ',true,2.25],['1e3',true,1000],['abc',false,null],['Infinity',false,null],['NaN',false,null],['0.0004',false,null],['0.001',true,0.001]].every(([i,k,v])=>pq(i).ok===k&&(k?pq(i).value===v:true)),JSON.stringify(['abc','Infinity'].map(pq)));
  ok('parsePrice: -1 y Infinity inválidos, 1500.4 -> 1500',!pp('-1').ok&&!pp('Infinity').ok&&pp('1500.4').value===1500);

  // ---- pestaña Precios
  ev("S.tab='prices'");await ev('renderPrices()');await tick();await tick();
  const priceCell=()=>main.querySelector('tbody').kids[0].kids[2].kids[0];
  const pcalls=calls.length;priceCell().value='-5';priceCell().dispatch('change');await tick();
  ok('Precios: -5 se rechaza sin llamar al servidor y se restaura el valor',!calls.slice(pcalls).some(x=>x.method==='PUT')&&String(priceCell().value)==='3000',String(priceCell().value));
  priceCell().value='3500';priceCell().dispatch('change');await tick();await tick();
  const pc=calls.slice(pcalls).find(x=>x.method==='PUT');
  ok('Precios: 3500 sí se guarda y actualiza el precio local',pc&&pc.body.amount===3500&&ev('S.products[0].prices.RETAIL')===3500,JSON.stringify(pc));

  // guardar: une duplicados y no manda filas repetidas
  const it=(n,p,q,u,mp=null)=>({id:null,name:n,productId:p,quantity:q,unit:u,manualPrice:mp});
  const merged=ev("payloadItems")([it('Papa criolla',1,2,'LB'),it('Papa criolla',1,3,'LB'),it('Tomate chonto',3,1,'LB')]);
  ok('payloadItems une repetidas (2+3=5)',merged.length===2&&merged[0].quantity===5);
  let threw=false;try{ev("payloadItems")([it('Papa criolla',1,2,'LB',2500),it('Papa criolla',1,3,'LB',null)])}catch(e){threw=e.code==='LOCAL'}
  ok('precios manuales distintos en repetidas: se rechaza',threw);
  ok('mismo producto en otra unidad NO se une (lo bloquea el servidor)',ev("payloadItems")([it('Papa',1,2,'LB'),it('Papa',1,2,'KG')]).length===2);

  // ---- Pruebas de flujo Mayorista / Fruver El Oasis
  serverOrders=[{
    id:10008,version:1,status:'APPROVED',conversationStatus:'CONFIRMED',reviewStatus:'GREEN',inputType:'IMAGE',aiStatus:'OK',
    customer:{id:2,name:'Restaurante El Sol',phoneMasked:'****1234',type:'WHOLESALE'},address:'Calle 10 # 5-20',notes:'',
    receivedAt:new Date().toISOString(),messageText:'Foto lista mayorista',hasAudio:false,hasImage:true,total:null,
    saleId:501,printStatus:'PRINTED',printedPrinter:'OFICINA',targetPrinter:'OFICINA',requiresVerification:false,
    items:[{id:201,productId:1,productName:'Papa criolla',quantity:50,unit:'LB'}]
  },{
    id:10009,version:1,status:'APPROVED',conversationStatus:'CONFIRMED',reviewStatus:'YELLOW',inputType:'AUDIO',aiStatus:'OK',
    customer:{id:3,name:'Casino Norte',phoneMasked:'****5678',type:'WHOLESALE'},address:'Carrera 15',notes:'',
    receivedAt:new Date().toISOString(),messageText:'audio con ruido',hasAudio:true,hasImage:false,total:null,
    saleId:502,printStatus:'PRINT_FAILED',printedPrinter:null,targetPrinter:'CASA',requiresVerification:true,verificationReason:'Audio poco claro',
    items:[{id:202,productId:3,productName:'Tomate chonto',quantity:20,unit:'LB'}]
  }];
  ev("S.tab='ops'");ev('S.draft={}');await ev('loadOrders()');
  const card8=cardOf(10008),card9=cardOf(10009);
  ok('Pedido 10008 aprobado se dibuja en tablero',!!card8);
  ok('Pedido 10008 muestra badge de FOTO',card8&&card8.textContent.includes('FOTO'));
  ok('Pedido 10008 muestra impreso en OFICINA',card8&&card8.textContent.includes('IMPRESO EN OFICINA'));
  ok('Pedido 10008 muestra VENDIDA',card8&&card8.textContent.includes('VENDIDA'));
  ok('Pedido 10008 muestra botón Agregar más cosas',card8&&card8.querySelectorAll('button').some(b=>b.textContent.includes('Agregar más cosas')));
  ok('Pedido 10008 muestra botón Ver Foto de Lista',card8&&card8.querySelectorAll('button').some(b=>b.textContent.includes('Ver Foto')));
  ok('Pedido 10009 muestra DUDOSO / REVISAR',card9&&card9.textContent.includes('DUDOSO')&&card9.textContent.includes('Audio poco claro'));
  ok('Pedido 10009 muestra FALLÓ IMPRESIÓN (CASA)',card9&&card9.textContent.includes('FALLÓ IMPRESIÓN (CASA)'));
  ok('Pedido 10009 tiene botón REINTENTAR IMPRESIÓN',card9&&card9.querySelectorAll('button').some(b=>b.textContent.includes('REINTENTAR IMPRESIÓN')));

  // Banner de alerta cuando hay fallos de impresión
  ev("S.printerStatus={activePrinter:'OFICINA',failedCount:2,pendingCount:1}");
  await ev('loadOrders()');
  const alertEl=main.children.find(c=>c.dataset&&c.dataset.id==='printer-alert');
  ok('Banner de alerta visible cuando hay pedidos sin imprimir',!!alertEl);
  ok('Banner tiene botón IMPRIMIR TODOS PENDIENTES',alertEl&&alertEl.querySelectorAll('button').some(b=>b.textContent==='🖨️ IMPRIMIR TODOS PENDIENTES'));

  // ---- Pruebas de Spin / IA interpretando (sin botones prematuros)
  serverOrders=[{
    id:10010,version:1,status:'PROCESSING',conversationStatus:'WAITING_FOR_MORE',reviewStatus:'GRAY',inputType:'IMAGE',aiStatus:'PENDING',
    customer:{id:4,name:'Doña Juana',phoneMasked:'****9999',type:'RETAIL'},address:'Calle 5',notes:'',
    receivedAt:new Date().toISOString(),messageText:'Lista en foto',hasAudio:false,hasImage:true,total:null,items:[]
  }];
  ev('S.draft={}');await ev('loadOrders()');
  const card10=cardOf(10010);
  ok('Pedido 10010 con IA interpretando se dibuja',!!card10);
  ok('Pedido 10010 muestra spinner animado .spin-loader',!!card10.querySelector('.spin-loader'));
  ok('Pedido 10010 explica que la IA está interpretando',card10.textContent.includes('La Inteligencia Artificial está interpretando'));
  ok('Pedido 10010 muestra pill de INTERPRETANDO',card10.textContent.includes('INTERPRETANDO'));
  ok('Pedido 10010 NO tiene ningún botón mientras piensa (mejora UX/UI)',card10.querySelectorAll('button').length===0);
  ok('Pedido 10010 NO muestra tabla de productos mientras piensa',!card10.querySelector('tbody'));

  // Al terminar la IA, se actualiza a OK y se habilitan los botones y tabla
  serverOrders=[{
    id:10010,version:2,status:'REVIEW',conversationStatus:'WAITING_FOR_MORE',reviewStatus:'GREEN',inputType:'IMAGE',aiStatus:'OK',
    customer:{id:4,name:'Doña Juana',phoneMasked:'****9999',type:'RETAIL'},address:'Calle 5',notes:'',
    receivedAt:new Date().toISOString(),messageText:'Lista en foto',hasAudio:false,hasImage:true,total:15000,
    items:[{id:203,productId:1,productName:'Papa criolla',quantity:3,unit:'LB'}]
  }];
  await ev('loadOrders()');
  const card10Done=cardOf(10010);
  ok('Al finalizar IA: spinner desaparece',!card10Done.querySelector('.spin-loader'));
  ok('Al finalizar IA: aparecen los botones de acción',card10Done.querySelectorAll('button').some(b=>b.textContent.includes('VALIDAR')));
  ok('Al finalizar IA: aparece la tabla con los productos extraídos',!!card10Done.querySelector('tbody'));

  console.log(fails?`\nFALLAS: ${fails}`:'\nTODO OK');process.exit(fails?1:0)
})();
