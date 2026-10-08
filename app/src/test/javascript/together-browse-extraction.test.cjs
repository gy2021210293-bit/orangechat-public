// Run from repository root: node app/src/test/javascript/together-browse-extraction.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/java/me/rerere/rikkahub/ui/pages/share/handler/TogetherBrowsePage.kt', 'utf8');
const script = source.match(/private const val TOGETHER_SNAPSHOT_SCRIPT = """([\s\S]*?)"""/)[1];

function extract({host, path, currentText, anchors = [], comments = [], images = [], body = currentText, state}) {
  const article = {innerText: currentText, querySelectorAll: () => images,};
  const main = {innerText: currentText, closest: () => article};
  const document = {
    title: 'Current post',
    body: {innerText: body, querySelectorAll: () => images},
    querySelectorAll(selector) {
      if (selector === '[data-testid="tweetText"]') return host === 'x.com' ? [main] : [];
      if (selector === '.narmal-note-container .content-container') return host.includes('xiaohongshu') ? [main] : [];
      if (selector === '.comment-item') return comments.map(text => ({innerText: text}));
      if (selector === 'a[href]') return anchors.map(({url, title}) => ({
        href: url,
        innerText: title,
        getAttribute: () => title,
        getClientRects: () => [{}],
        closest: () => ({innerText: title}),
      }));
      return [];
    },
    querySelector: selector => selector === '.narmal-note-container' ? article : null,
  };
  const location = {hostname: host, pathname: path, href: `https://${host}${path}`};
  return JSON.parse(vm.runInNewContext(script, {document, location, URL, window: {__INITIAL_STATE__: state}}));
}

test('X snapshot includes the current post and only visible X candidate links', () => {
  const result = extract({
    host: 'x.com', path: '/user/status/1', currentText: 'Current X post',
    anchors: [
      {url: 'https://x.com/user/status/2', title: 'Visible recommendation'},
      {url: 'https://example.com/outside', title: 'External link'},
    ],
  });
  assert.equal(result.text, 'Current X post');
  assert.equal(result.candidates.length, 1);
  assert.equal(result.candidates[0].url, 'https://x.com/user/status/2');
});

test('Xiaohongshu snapshot includes unloaded carousel images but excludes avatars', () => {
  const image = (src, post) => ({
    src, currentSrc: '', naturalWidth: 0, naturalHeight: 0,
    closest: () => post ? {} : null, getClientRects: () => [],
    getAttribute: () => null,
  });
  const result = extract({
    host: 'www.xiaohongshu.com', path: '/explore/012345678901234567890123', currentText: 'Note',
    images: [image('http://sns-webpic-qc.xhscdn.com/post.jpg', true), image('https://www.xiaohongshu.com/avatar.jpg', false)],
  });
  assert.deepEqual(result.images, ['http://sns-webpic-qc.xhscdn.com/post.jpg']);
});

test('Xiaohongshu discovery post uses image data from matching initial state', () => {
  const id = '6a51d05a0000000016024f02';
  const state = {note: {noteDetailMap: {[id]: {note: {noteId: id, imageList: [
    {url: '', infoList: [{imageScene: 'WB_DFT', url: 'http://sns-webpic-qc.xhscdn.com/default.jpg'}]},
  ]}}}}};
  const result = extract({host: 'www.xiaohongshu.com', path: `/discovery/item/${id}`,
    currentText: 'Note', state});
  assert.deepEqual(result.images, ['http://sns-webpic-qc.xhscdn.com/default.jpg']);
});

test('Xiaohongshu snapshot includes loaded comments and excludes candidates on login pages', () => {
  const result = extract({
    host: 'www.xiaohongshu.com', path: '/login', currentText: 'Sign in',
    body: 'Sign in to continue',
    comments: ['private comment'],
    anchors: [{url: 'https://www.xiaohongshu.com/explore/012345678901234567890123', title: 'A note'}],
  });
  assert.equal(result.requiresLogin, true);
  assert.equal(result.text, '');
  assert.deepEqual(result.comments, []);
  assert.deepEqual(result.candidates, []);
});

test('Xiaohongshu snapshot reads loaded comments and visible same-platform note candidates', () => {
  const result = extract({
    host: 'www.xiaohongshu.com', path: '/explore/012345678901234567890123', currentText: 'Current note',
    comments: ['Loaded comment'],
    anchors: [
      {url: 'https://www.xiaohongshu.com/explore/112345678901234567890123', title: 'Visible note'},
      {url: 'xhsdiscover://item/212345678901234567890123', title: 'App-linked note'},
      {url: 'https://example.com/outside', title: 'External page'},
    ],
  });
  assert.deepEqual(result.comments, ['Loaded comment']);
  assert.equal(result.candidates.length, 2);
  assert.equal(result.candidates[0].title, 'Visible note');
  assert.equal(result.candidates[1].url, 'https://www.xiaohongshu.com/explore/212345678901234567890123');
});

test('ordinary websites expose visible page text and web candidates', () => {
  const result = extract({
    host: 'example.com', path: '/article', currentText: 'Article content',
    anchors: [
      {url: 'https://example.com/related', title: 'Related article'},
      {url: 'https://other.example.net/story', title: 'Another article'},
      {url: 'javascript:alert(1)', title: 'Unsafe link'},
    ],
  });
  assert.equal(result.text, 'Article content');
  assert.deepEqual(result.candidates.map(candidate => candidate.url), [
    'https://example.com/related',
    'https://other.example.net/story',
  ]);
});
