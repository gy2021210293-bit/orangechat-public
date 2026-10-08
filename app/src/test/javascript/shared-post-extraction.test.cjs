// Run from repository root: node app/src/test/javascript/shared-post-extraction.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/me/rerere/rikkahub/ui/pages/share/handler/SharedPostSharePage.kt', 'utf8');
const script = source.match(/private const val EXTRACT_POST_SCRIPT = """([\s\S]*?)"""/)[1];
function extract({host='example.com', path='/', text='', selector='', images=[], comments=[], body=text, state}) {
  const root = {querySelectorAll: () => images};
  const node = {innerText: text, parentElement: {parentElement: root}, closest: () => root};
  const document = {
    title: 'Test', body: {innerText: body, querySelectorAll: () => images},
    querySelectorAll: value => value === '.comment-item' ? comments.map(innerText => ({innerText})) : value === selector ? [node] : [],
    querySelector: value => value === '.narmal-note-container' || value === '#detail-entry' ? root : null,
  };
  const location = {hostname:host, pathname:path, href:`https://${host}${path}`};
  return JSON.parse(vm.runInNewContext(script, {document, location, URL, window: {__INITIAL_STATE__: state}}));
}
function image(src, post=true) {
  return {src,currentSrc:src,naturalWidth:0,naturalHeight:0,
    closest:()=>post ? {} : null,getClientRects:()=>[{}],getAttribute:()=>null,hasAttribute:()=>false};
}
test('short mobile Xiaohongshu text and unloaded carousel images are retained, avatars excluded', () => {
  const result=extract({host:'www.xiaohongshu.com',text:'杀个柚子助助兴',selector:'.narmal-note-container .content-container',
    images:[image('http://sns-webpic-qc.xhscdn.com/post.jpg'),image('https://avatar.example/a.jpg',false)]});
  assert.equal(result.text,'杀个柚子助助兴');
  assert.deepEqual(result.images,['http://sns-webpic-qc.xhscdn.com/post.jpg']);
});
test('loaded Xiaohongshu image URL takes precedence over stale lazy attribute', () => {
  const loaded = {...image('https://sns-webpic-qc.xhscdn.com/post.jpg'),
    getAttribute: name => name === 'data-original' ? 'https://sns-webpic-qc.xhscdn.com/expired.jpg' : null};
  const result = extract({host:'www.xiaohongshu.com',text:'正文',selector:'#detail-desc',images:[loaded]});
  assert.deepEqual(result.images,['https://sns-webpic-qc.xhscdn.com/post.jpg']);
});
test('Xiaohongshu discovery post reads image from matching initial state when DOM has no image', () => {
  const id = '6a51d05a0000000016024f02';
  const state = {note: {noteDetailMap: {[id]: {note: {noteId: id, imageList: [
    {url: '', urlDefault: 'http://sns-webpic-qc.xhscdn.com/default.jpg', urlPre: 'http://sns-webpic-qc.xhscdn.com/preview.jpg'},
  ]}}}}};
  const result = extract({host: 'www.xiaohongshu.com', path: `/discovery/item/${id}`, text: '正文',
    selector: '#detail-desc', state});
  assert.deepEqual(result.images, ['http://sns-webpic-qc.xhscdn.com/default.jpg']);
});
test('Taobao mobile first card and product photo are extracted without promotional graphics', () => {
  const result=extract({host:'h5.m.taobao.com',text:'￥29\n商品标题',selector:'#detail-entry [class*="first_card"]',
    images:[image('https://img.alicdn.com/product.jpg'),image('https://gw.alicdn.com/promotion.png')]});
  assert.equal(result.text,'￥29\n商品标题');
  assert.deepEqual(result.images,['https://img.alicdn.com/product.jpg']);
});
test('login and failure pages never produce text or images', () => {
  for(const body of ['请先登录','网页无法打开']) {
    const result=extract({text:'页面正文',selector:'article',body,images:[image('https://example.com/a.jpg')]});
    assert.equal(result.text,''); assert.deepEqual(result.images,[]);
  }
});
test('generic visible text is explicitly labelled and empty pages stay empty', () => {
  assert.match(extract({text:'',body:'可见页面'}).text,/页面可见文字/);
  assert.equal(extract({}).text,'');
});
test('image URLs resolve relative paths, deduplicate and stop at nine', () => {
  const images=Array.from({length:12},(_,i)=>({...image(`/image${i}.jpg`),naturalWidth:200,naturalHeight:200}));
  const result=extract({text:'正文',selector:'article',images:[images[0],...images]});
  assert.equal(result.images.length,9); assert.equal(result.images[0],'https://example.com/image0.jpg');
});

test('full long post and loaded comments survive extraction without truncation', () => {
  const text = 'a'.repeat(16000);
  const result = extract({host:'www.xiaohongshu.com', text, selector:'#detail-desc', comments:['reader: first comment','reader: second comment']});
  assert.ok(result.text.startsWith(text));
  assert.ok(result.text.includes('reader: first comment'));
  assert.ok(result.text.endsWith('reader: second comment'));
});
test('login gate excludes loaded comments too', () => {
  const result = extract({host:'www.xiaohongshu.com', path:'/login', text:'login', comments:['private comment']});
  assert.equal(result.text, '');
});
