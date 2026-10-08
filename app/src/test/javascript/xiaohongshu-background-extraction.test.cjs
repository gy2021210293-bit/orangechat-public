// Run from repository root: node app/src/test/javascript/xiaohongshu-background-extraction.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/java/me/rerere/rikkahub/data/ai/tools/XiaohongshuBrowseTool.kt', 'utf8');
const script = source.match(/private const val XHS_SNAPSHOT_SCRIPT = """([\s\S]*?)"""/)[1];

function extract({path, bodyText, noteText = '', anchors = [], comments = [], note = null}) {
  const detail = {innerText: noteText, querySelectorAll: () => []};
  const main = {innerText: noteText, closest: () => detail};
  const document = {
    title: '小红书页面',
    body: {innerText: bodyText, querySelectorAll: () => []},
    querySelectorAll(selector) {
      if (selector === '.narmal-note-container .content-container') return noteText ? [main] : [];
      if (selector === '.comment-item') return comments.map(text => ({innerText: text}));
      if (selector === 'a[href]') return anchors.map(({url, title}) => ({
        href: url,
        innerText: title,
        getAttribute: () => title,
        closest: () => ({innerText: title}),
      }));
      return [];
    },
    querySelector(selector) {
      if (selector === '.narmal-note-container .content-container') return noteText ? main : null;
      return null;
    },
  };
  const location = {hostname: 'www.xiaohongshu.com', pathname: path, href: `https://www.xiaohongshu.com${path}`};
  const noteId = path.split('/').filter(Boolean).pop();
  const window = {__INITIAL_STATE__: {note: {noteDetailMap: note ? {[noteId]: {note}} : {}}}};
  return JSON.parse(vm.runInNewContext(script, {document, location, URL, window}));
}

test('extracts note body, loaded comments and same-site note candidates', () => {
  const result = extract({
    path: '/explore/012345678901234567890123',
    bodyText: '周边页面信息',
    noteText: '这是帖子正文',
    comments: ['已加载评论'],
    anchors: [
      {url: 'https://www.xiaohongshu.com/explore/112345678901234567890123', title: '推荐帖子'},
      {url: 'https://xiaohongshu.com.attacker.example/explore/212345678901234567890123', title: '仿冒域名'},
      {url: 'https://example.com/post', title: '站外链接'},
    ],
  });

  assert.equal(result.text, '这是帖子正文');
  assert.equal(result.postReady, true);
  assert.deepEqual(result.comments, ['已加载评论']);
  assert.deepEqual(result.candidates, [
    {title: '推荐帖子', url: 'https://www.xiaohongshu.com/explore/112345678901234567890123'},
  ]);
});

test('a search shell is not mistaken for note content', () => {
  const result = extract({path: '/search_result/', bodyText: '全部 图文 视频 用户'});

  assert.equal(result.postReady, false);
  assert.deepEqual(result.candidates, []);
});

test('post without detail does not fall back to the whole page', () => {
  const result = extract({path: '/explore/012345678901234567890123', bodyText: '扫码查看'});

  assert.equal(result.postReady, false);
  assert.equal(result.text, '');
});

test('matching state note supplies post text without a rendered detail', () => {
  const result = extract({
    path: '/explore/012345678901234567890123',
    bodyText: '页面导航',
    note: {noteId: '012345678901234567890123', title: '真实标题', desc: '真实正文'},
  });

  assert.equal(result.postReady, true);
  assert.equal(result.text, '真实标题 真实正文');
});

test('matching state note supplies image URLs for an image-only post', () => {
  const result = extract({
    path: '/explore/012345678901234567890123',
    bodyText: '页面导航',
    note: {noteId: '012345678901234567890123', imageList: [{urlDefault: 'https://example.xhscdn.com/post.jpg'}]},
  });

  assert.equal(result.postReady, true);
  assert.deepEqual(result.images, ['https://example.xhscdn.com/post.jpg']);
});

test('unavailable note page never reports post content', () => {
  const result = extract({
    path: '/explore/012345678901234567890123',
    bodyText: '当前笔记暂时无法浏览 请打开小红书App扫码查看',
    noteText: '扫码查看',
  });

  assert.equal(result.unavailable, true);
  assert.equal(result.postReady, false);
  assert.equal(result.text, '');
});

test('login pages expose no post text, comments or candidates', () => {
  const result = extract({
    path: '/login',
    bodyText: '登录后查看笔记',
    noteText: '不应作为正文的登录内容',
    comments: ['不可读取'],
    anchors: [{url: 'https://www.xiaohongshu.com/explore/112345678901234567890123', title: '候选'}],
  });

  assert.equal(result.requiresLogin, true);
  assert.equal(result.text, '');
  assert.deepEqual(result.comments, []);
  assert.deepEqual(result.candidates, []);
});
