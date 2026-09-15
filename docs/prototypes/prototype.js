const $ = (selector) => document.querySelector(selector);
const states = { CREATED: ['已创建','created'], ASSIGNED: ['待接单','assigned'], PROCESSING: ['处理中','processing'], PENDING: ['等待用户','pending'], RESOLVED: ['已解决','resolved'], CLOSED: ['已关闭','closed'] };
const tickets = [
  {id:1042,title:'无法连接公司 VPN，内部文档无法访问',category:'网络与连接',status:'ASSIGNED',priority:'高',sla:'响应剩余 18 分钟',deadline:'今天 10:12 截止',urgent:true},
  {id:1038,title:'企业邮箱无法接收外部邮件',category:'办公软件',status:'PROCESSING',priority:'紧急',sla:'解决已超时 32 分钟',deadline:'今天 09:22 截止',urgent:true,breached:true},
  {id:1035,title:'申请开通项目管理平台访问权限',category:'账号与权限',status:'ASSIGNED',priority:'普通',sla:'响应剩余 2 小时',deadline:'今天 12:00 截止'},
  {id:1032,title:'会议室投屏设备无法识别笔记本',category:'硬件设备',status:'PROCESSING',priority:'高',sla:'解决剩余 3 小时',deadline:'今天 13:00 截止'},
  {id:1029,title:'设计软件启动时提示许可证过期',category:'办公软件',status:'PENDING',priority:'普通',sla:'明天 09:30 截止',deadline:'等待用户补充信息'},
  {id:1025,title:'新入职员工电脑初始化配置',category:'硬件设备',status:'PROCESSING',priority:'普通',sla:'明天 14:00 截止',deadline:'解决时限'},
  {id:1021,title:'共享打印机无法正常连接',category:'网络与连接',status:'RESOLVED',priority:'低',sla:'已按时解决',deadline:'等待关闭'},
  {id:1018,title:'重置企业门户登录密码',category:'账号与权限',status:'CLOSED',priority:'普通',sla:'已完成',deadline:'昨天 16:20 关闭'},
];
let view = 'work', filter = '', urgentOnly = false, current = tickets[0], toastTimer;
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function toast(message) { $('#toast').textContent=message; $('#toast').hidden=false; clearTimeout(toastTimer); toastTimer=setTimeout(()=>$('#toast').hidden=true,3500); }
function statusName(ticket) {return view==='mine'&&ticket.status==='PENDING'?'待我补充':states[ticket.status][0];}
function badge(ticket) { return `<span class="status ${states[ticket.status][1]}">${statusName(ticket)}</span>`; }
function renderList() {
  const query = $('#search').value.trim().toLowerCase(), category = $('#category').value;
  const visible=tickets.filter(t=>(!filter||t.status===filter)&&(!urgentOnly||t.urgent)&&(!category||t.category===category)&&(`${t.id} ${t.title}`.toLowerCase().includes(query)));
  if(view==='mine') visible.sort((a,b)=>Number(b.status==='PENDING')-Number(a.status==='PENDING'));
  const tabs=view==='mine'?[['','全部'],['PENDING','待我补充'],['PROCESSING','处理中'],['RESOLVED','已解决']]:[['','全部'],['ASSIGNED','待接单'],['PROCESSING','处理中'],['PENDING','等待用户']];
  $('.tabs').innerHTML=tabs.map(([state,name])=>`<button class="tab" role="tab" tabindex="${filter===state?'0':'-1'}" aria-selected="${filter===state}" data-filter="${state}">${name}<span class="count">${tickets.filter(t=>!state||t.status===state).length}</span></button>`).join('');
  $('#tickets').innerHTML=visible.map(t=>`<article class="ticket-row ticket-grid ${t.urgent?'attention-row':''} ${t.breached?'breached-row':''}"><div><a class="ticket-title" href="#ticket/${t.id}">${escapeHtml(t.title)}</a><div class="ticket-meta"><span class="number">#${t.id}</span><span>${t.category}</span></div></div>${badge(t)}<span class="priority ${t.priority==='紧急'?'urgent':t.priority==='高'?'high':''}">${t.priority==='紧急'?'↑↑':t.priority==='高'?'↑':'−'} ${t.priority}</span><div class="agent-cell"><span class="avatar mini">陈</span>陈敏</div><div class="sla-cell ${t.breached?'breached':t.urgent?'warning':''}">${t.sla}<small>${t.deadline}</small></div></article>`).join('');
  $('.result-count').textContent=`${visible.length} 张工单`; $('.empty').hidden=visible.length!==0;
  $('#attention').hidden=view==='mine'; $('#new-ticket').hidden=view!=='mine'; $('#online').hidden=view!=='work';
  $('#page-title').textContent=view==='mine'?'我的工单':'我的工作台';
  $('#page-description').textContent=view==='mine'?'提出问题、补充信息，随时了解处理进展。':'先处理需要你行动的事，让服务有序向前。';
}
function renderDetail() {
  $('.composer-top label').textContent=view==='mine'?'补充信息':'回复用户';
  $('#ticket-number').textContent=`#${current.id}`; $('#detail-title').textContent=current.title;
  $('#detail-badge').className=`status ${states[current.status][1]}`; $('#detail-badge').textContent=statusName(current);
  $('#detail-category').textContent=current.category; $('#detail-priority').textContent=current.priority; $('#detail-sla').textContent=current.sla;
  $('#back-link').href=`#${view}`; $('#back-link').textContent=view==='mine'?'‹ 返回我的工单':'‹ 返回工作台';
  $('#issue-description').textContent=current.description || (current.id===1042?'今天早上连接公司 VPN 时一直提示“连接超时”，重启电脑后仍无法连接。现在无法访问内部项目文档，希望协助排查。':`关于“${current.title}”的问题，希望服务团队协助排查。此处为布局示例内容。`);
  const action={ASSIGNED:'开始处理',PROCESSING:'标记已解决',RESOLVED:'关闭工单'}[current.status];
  $('#main-action').hidden=view==='mine'||!action; $('#main-action').textContent=action||'';
  $('#reply-form').hidden=['CLOSED'].includes(current.status); $('#ai-panel').hidden=view==='mine';
  $('#new-messages').replaceChildren(); $('#reply').value='';
}
function route() {
  const hash=location.hash.slice(1)||'work', isDetail=hash.startsWith('ticket/');
  if (!isDetail) {const nextView=hash==='mine'?'mine':'work';if(nextView!==view){filter='';urgentOnly=false;$('#search').value='';$('#category').value='';}view=nextView;}
  if(isDetail) { current=tickets.find(t=>t.id===Number(hash.split('/')[1]))||tickets[0]; renderDetail(); } else renderList();
  $('#list-view').hidden=isDetail; $('#detail-view').hidden=!isDetail;
  $('#breadcrumb').textContent=isDetail?'工单详情':view==='mine'?'我的工单':'我的工作台';
  $('#persona').textContent=view==='mine'?'员工 · 提交问题':'客户支持 · 客服';
  $('#profile-name').textContent=view==='mine'?'林晓':'陈敏';$('#profile-avatar').textContent=view==='mine'?'林':'陈';
  document.querySelectorAll('[data-nav]').forEach(a=>{a.classList.toggle('active',a.dataset.nav===view);a.setAttribute('aria-current',a.dataset.nav===view?'page':'false');});
}
$('.tabs').addEventListener('click',e=>{const tab=e.target.closest('[data-filter]');if(tab){filter=tab.dataset.filter;urgentOnly=false;renderList();}});
$('.tabs').addEventListener('keydown',e=>{if(!['ArrowLeft','ArrowRight','Home','End'].includes(e.key))return;e.preventDefault();const items=[...document.querySelectorAll('.tab')];const index=items.indexOf(document.activeElement);const target=e.key==='Home'?0:e.key==='End'?items.length-1:(index+(e.key==='ArrowRight'?1:-1)+items.length)%items.length;const state=items[target].dataset.filter;filter=state;urgentOnly=false;renderList();document.querySelector(`[data-filter="${state}"]`).focus();});
$('#search').addEventListener('input',renderList); $('#category').addEventListener('change',renderList);
function reset(){filter='';urgentOnly=false;$('#search').value='';$('#category').value='';renderList();}
$('#reset').onclick=reset;$('#empty-reset').onclick=reset;$('#urgent-filter').onclick=()=>{reset();urgentOnly=true;renderList();};
$('#online').onclick=()=>toast('原型中保持在线。正式版本将调用客服在线状态接口。');
$('#new-ticket').onclick=()=>$('#create-dialog').showModal();
$('#close-dialog').onclick=$('#cancel-dialog').onclick=()=>$('#create-dialog').close();
$('#create-form').onsubmit=e=>{e.preventDefault();const title=$('#create-title').value.trim(),description=$('#create-description').value.trim();if(!title||!description){toast('请填写问题标题和描述');return;}const id=Math.max(...tickets.map(t=>t.id))+1;tickets.unshift({id,title,description,category:$('#create-category').value,status:'CREATED',priority:$('#create-priority').value,sla:'等待分配',deadline:'规则将在正式系统计算'});$('#create-dialog').close();$('#create-form').reset();reset();location.hash=`ticket/${id}`;toast('示例工单已创建，仅保留在本次预览。');};
$('#reply-form').onsubmit=e=>{e.preventDefault();const content=$('#reply').value.trim();if(!content){toast('请先填写回复内容');return;}const article=document.createElement('article');article.className='message';const avatar=document.createElement('span');avatar.className='avatar';avatar.textContent=view==='mine'?'林':'陈';const body=document.createElement('div');const author=document.createElement('div');author.className='message-author';author.textContent=view==='mine'?'林晓 · 刚刚':'陈敏 · 刚刚';const paragraph=document.createElement('p');paragraph.textContent=content;body.append(author,paragraph);article.append(avatar,body);$('#new-messages').append(article);$('#reply').value='';toast('示例回复已添加，未发送到后端。');};
$('#suggestion').onclick=()=>{$('#reply').value='您好，已收到您反馈的问题。我们正在检查网络连接服务，烦请补充错误提示的完整截图，以及最近一次成功连接的时间，方便进一步排查。';$('#reply').focus();toast('示例草稿已填入，请检查后再发送。');};
$('#close-action').onclick=$('#cancel-action').onclick=()=>$('#action-dialog').close();
function completeAction(){const next={ASSIGNED:'PROCESSING',PROCESSING:'RESOLVED',RESOLVED:'CLOSED'}[current.status];if(next){current.status=next;if(next==='RESOLVED'||next==='CLOSED')current.sla='已完成 · 演示状态';renderDetail();toast('已演示状态变化，未修改真实工单。');}}
$('#main-action').onclick=()=>{if(current.status==='ASSIGNED'){completeAction();return;}$('#action-title').textContent=$('#main-action').textContent;$('#action-description').textContent=current.status==='PROCESSING'?'解决说明将作为公开回复展示给用户。本次仅演示，不发送到后端。':'关闭后将无法继续回复，请确认问题已经解决。本次仅演示。';$('#action-form').reset();$('#action-dialog').showModal();};
$('#action-form').onsubmit=e=>{e.preventDefault();if(!$('#action-reason').value.trim())return;const reason=$('#action-reason').value;const isResolution=current.status==='PROCESSING';$('#action-dialog').close();completeAction();if(isResolution){const p=document.createElement('p');p.textContent=`陈敏已解决：${reason}`;$('#new-messages').append(p);}};
$('.skip').onclick=e=>{e.preventDefault();$('#main').focus();$('#main').scrollIntoView();};
window.addEventListener('hashchange',()=>{route();window.scrollTo(0,0);});route();
