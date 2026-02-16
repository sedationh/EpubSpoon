// ==UserScript==
// @name         EpubSpoon for Gemini
// @namespace    https://github.com/sedationh/EpubSpoon
// @version      1.0.0
// @description  导入 EPUB 英文书 → 在 Gemini 页面悬浮按钮点一下 → 自动填充并发送一段，像刷短视频一样读英文书
// @author       sedationh
// @match        https://gemini.google.com/*
// @grant        none
// @require      https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js
// @run-at       document-idle
// ==/UserScript==

(function () {
  'use strict';

  // ==================== TrustedTypes 兼容 ====================
  // Gemini 启用了 TrustedHTML CSP，需要创建 policy 来绕过 DOMParser 限制
  let trustedPolicy = null;
  try {
    if (window.trustedTypes && trustedTypes.createPolicy) {
      trustedPolicy = trustedTypes.createPolicy('epubspoon', {
        createHTML: (input) => input,
      });
    }
  } catch (e) {
    // policy 已存在或不支持，忽略
  }

  /**
   * 安全的 DOMParser.parseFromString 包装，兼容 TrustedTypes
   */
  function safeParse(htmlString, mimeType) {
    const parser = new DOMParser();
    if (trustedPolicy) {
      return parser.parseFromString(trustedPolicy.createHTML(htmlString), mimeType);
    }
    return parser.parseFromString(htmlString, mimeType);
  }

  // ==================== 常量 ====================
  const STORAGE_KEY_SEGMENTS = 'epubspoon_segments';
  const STORAGE_KEY_PROGRESS = 'epubspoon_progress';
  const STORAGE_KEY_TITLE = 'epubspoon_title';
  const STORAGE_KEY_MD5 = 'epubspoon_md5';

  // 默认母指令模板
  const DEFAULT_INSTRUCTION = `You are my English reading assistant. I will send you passages from an English book one at a time. For each passage, please respond in the following format:

## Translation
Translate every sentence into Chinese, keeping the original sentence order. Place the English sentence first, followed by the Chinese translation on the next line, with a blank line between each pair.

## Key Vocabulary
List 5-10 important or difficult words/phrases from this passage in a table:
| Word/Phrase | Meaning (Chinese) | Example from text |

## Summary
Summarize the main idea of this passage in 2-3 sentences in Chinese.

---
Keep this format consistent for every passage I send. No need to confirm or repeat instructions. Just wait for my first passage.`;

  // ==================== 工具函数 ====================

  /**
   * 简易 MD5（用于缓存 key，非密码学用途）
   * 使用 SubtleCrypto 计算 SHA-256 的前 16 位 hex 作为唯一标识
   */
  async function calcFileHash(arrayBuffer) {
    const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('').substring(0, 32);
  }

  /**
   * 判断是否为目录页/版权页等无效内容
   */
  function isBoilerplate(text) {
    const lower = text.toLowerCase();
    const keywords = [
      'table of contents', 'contents', 'copyright',
      'all rights reserved', 'published by', 'isbn',
      'cover', 'title page'
    ];
    return keywords.some(kw => lower.includes(kw));
  }

  /**
   * 从 HTML 字符串提取纯文本，过滤非文本元素
   */
  function htmlToText(html) {
    const doc = safeParse(html, 'text/html');
    // 移除非文本元素
    doc.querySelectorAll('img, table, svg, script, style, nav').forEach(el => el.remove());
    return (doc.body?.textContent || '').trim();
  }

  /**
   * 智能分段：对单章文本按句子边界和字数权重分段。
   * 移植自 Android 版 SegmentHelper.kt
   */
  function segmentChapter(text, targetWords = 300) {
    // 句号/问号/叹号后跟空格+大写字母才算断句，避免 Mr. Dr. U.S. 等缩写误断
    const sentences = text.split(/(?<=[.!?])\s+(?=[A-Z])/);
    const result = [];
    let currentBatch = '';
    let currentCount = 0;

    for (const sentence of sentences) {
      const wordCount = sentence.split(/\s+/).length;

      if (currentCount + wordCount > targetWords && currentCount > 0) {
        result.push(currentBatch.trim());
        currentBatch = '';
        currentCount = 0;
      }

      currentBatch += sentence + ' ';
      currentCount += wordCount;
    }

    if (currentBatch.trim()) {
      result.push(currentBatch.trim());
    }

    return result;
  }

  /**
   * 对多章文本进行智能分段，保留章节边界。不跨章节拼接。
   */
  function getSmartSegments(chapterTexts, targetWords = 300) {
    const allSegments = [];
    for (const chapterText of chapterTexts) {
      const trimmed = chapterText.trim();
      if (!trimmed) continue;
      const segments = segmentChapter(trimmed, targetWords);
      allSegments.push(...segments);
    }
    return allSegments;
  }

  /**
   * 解析 EPUB 文件（ZIP），提取各章纯文本
   */
  async function parseEpub(arrayBuffer) {
    const zip = await JSZip.loadAsync(arrayBuffer);

    // 1. 读取 META-INF/container.xml 获取 OPF 路径
    const containerXml = await zip.file('META-INF/container.xml')?.async('text');
    if (!containerXml) throw new Error('无法读取 container.xml');

    const containerDoc = safeParse(containerXml, 'text/xml');
    const rootfileEl = containerDoc.querySelector('rootfile');
    const opfPath = rootfileEl?.getAttribute('full-path');
    if (!opfPath) throw new Error('无法找到 OPF 文件路径');

    // OPF 所在目录，用于拼接相对路径
    const opfDir = opfPath.includes('/') ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : '';

    // 2. 读取 OPF 文件
    const opfXml = await zip.file(opfPath)?.async('text');
    if (!opfXml) throw new Error('无法读取 OPF 文件');

    const opfDoc = safeParse(opfXml, 'text/xml');

    // 获取书名
    const titleEl = opfDoc.querySelector('metadata title, dc\\:title');
    const bookTitle = titleEl?.textContent?.trim() || 'Unknown';

    // 3. 构建 manifest 映射 (id -> href)
    const manifest = {};
    opfDoc.querySelectorAll('manifest item').forEach(item => {
      const id = item.getAttribute('id');
      const href = item.getAttribute('href');
      const mediaType = item.getAttribute('media-type');
      if (id && href) {
        manifest[id] = { href, mediaType };
      }
    });

    // 4. 按 spine 顺序读取 HTML 内容
    const spineItems = opfDoc.querySelectorAll('spine itemref');
    const chapterTexts = [];

    for (const itemref of spineItems) {
      const idref = itemref.getAttribute('idref');
      if (!idref || !manifest[idref]) continue;

      const { href, mediaType } = manifest[idref];
      // 只处理 HTML/XHTML 内容
      if (mediaType && !mediaType.includes('html') && !mediaType.includes('xml')) continue;

      const filePath = opfDir + href;
      const file = zip.file(filePath) || zip.file(decodeURIComponent(filePath));
      if (!file) continue;

      const html = await file.async('text');
      const text = htmlToText(html);

      // 跳过空章节和疑似目录/版权页
      if (!text) continue;
      if (text.length < 100 && isBoilerplate(text)) continue;

      chapterTexts.push(text);
    }

    return { bookTitle, chapterTexts };
  }

  // ==================== 存储管理 ====================

  function saveSegments(segments) {
    localStorage.setItem(STORAGE_KEY_SEGMENTS, JSON.stringify(segments));
  }

  function loadSegments() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY_SEGMENTS);
      return raw ? JSON.parse(raw) : null;
    } catch { return null; }
  }

  function saveProgress(index) {
    localStorage.setItem(STORAGE_KEY_PROGRESS, String(index));
  }

  function loadProgress() {
    return parseInt(localStorage.getItem(STORAGE_KEY_PROGRESS) || '0', 10);
  }

  function saveTitle(title) {
    localStorage.setItem(STORAGE_KEY_TITLE, title);
  }

  function loadTitle() {
    return localStorage.getItem(STORAGE_KEY_TITLE) || '';
  }

  function saveMd5(md5) {
    localStorage.setItem(STORAGE_KEY_MD5, md5);
  }

  function loadMd5() {
    return localStorage.getItem(STORAGE_KEY_MD5) || '';
  }

  function clearAll() {
    localStorage.removeItem(STORAGE_KEY_SEGMENTS);
    localStorage.removeItem(STORAGE_KEY_PROGRESS);
    localStorage.removeItem(STORAGE_KEY_TITLE);
    localStorage.removeItem(STORAGE_KEY_MD5);
  }

  // ==================== Gemini 页面交互 ====================

  /**
   * 在 Gemini 输入框中填入文本并发送
   */
  function fillAndSend(text) {
    // Gemini 使用 rich-text-field 或 contenteditable 的输入区域
    // 尝试多种选择器以兼容不同版本
    const inputEl =
      document.querySelector('.ql-editor[contenteditable="true"]') ||
      document.querySelector('rich-text-field .ql-editor') ||
      document.querySelector('[contenteditable="true"].textarea') ||
      document.querySelector('div[contenteditable="true"][role="textbox"]') ||
      document.querySelector('.input-area [contenteditable="true"]') ||
      document.querySelector('[contenteditable="true"]');

    if (!inputEl) {
      showToast('未找到 Gemini 输入框，请确保在 Gemini 对话页面');
      return false;
    }

    // 清空现有内容并填入（不用 innerHTML，Gemini 有 TrustedHTML CSP）
    while (inputEl.firstChild) inputEl.removeChild(inputEl.firstChild);
    // 直接设置 textContent 并触发 input 事件
    const p = document.createElement('p');
    p.textContent = text;
    inputEl.appendChild(p);

    // 触发 input 事件让 Gemini 感知内容变化
    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
    inputEl.dispatchEvent(new Event('change', { bubbles: true }));

    // 延迟一小段时间后点击发送按钮，等待 UI 更新
    setTimeout(() => {
      const sendBtn =
        document.querySelector('button.send-button') ||
        document.querySelector('button[aria-label="Send message"]') ||
        document.querySelector('button[aria-label="发送"]') ||
        document.querySelector('.send-button-container button') ||
        document.querySelector('button[mat-icon-button][aria-label*="Send"]') ||
        document.querySelector('.input-area-container button.send-button') ||
        // 通用：找包含发送图标的按钮
        findSendButton();

      if (sendBtn) {
        sendBtn.click();
      } else {
        showToast('已填入内容，请手动点击发送');
      }
    }, 300);

    return true;
  }

  /**
   * 尝试用多种方式找到发送按钮
   */
  function findSendButton() {
    // 方法1: 找 mat-icon 中包含 send 的按钮
    const buttons = document.querySelectorAll('button');
    for (const btn of buttons) {
      const ariaLabel = (btn.getAttribute('aria-label') || '').toLowerCase();
      if (ariaLabel.includes('send') || ariaLabel.includes('发送') || ariaLabel.includes('submit')) {
        return btn;
      }
    }
    // 方法2: 找 .send-button 类
    for (const btn of buttons) {
      if (btn.classList.contains('send-button')) return btn;
    }
    return null;
  }

  // ==================== UI 组件 ====================

  let panelVisible = false;
  let floatingBtn = null;
  let panel = null;
  let segments = [];
  let currentIndex = 0;
  let bookTitle = '';

  function showToast(msg, duration = 2500) {
    const toast = document.createElement('div');
    toast.textContent = msg;
    Object.assign(toast.style, {
      position: 'fixed',
      bottom: '80px',
      left: '50%',
      transform: 'translateX(-50%)',
      background: 'rgba(0,0,0,0.8)',
      color: '#fff',
      padding: '10px 24px',
      borderRadius: '8px',
      fontSize: '14px',
      zIndex: '2147483647',
      transition: 'opacity 0.3s',
      pointerEvents: 'none',
      whiteSpace: 'pre-line',
    });
    document.body.appendChild(toast);
    setTimeout(() => {
      toast.style.opacity = '0';
      setTimeout(() => toast.remove(), 300);
    }, duration);
  }

  /**
   * 创建悬浮按钮（和 Android 版外观一致）
   */
  function createFloatingButton() {
    if (floatingBtn) floatingBtn.remove();

    floatingBtn = document.createElement('div');
    floatingBtn.id = 'epubspoon-float-btn';
    Object.assign(floatingBtn.style, {
      position: 'fixed',
      top: '120px',
      right: '24px',
      width: '56px',
      height: '56px',
      borderRadius: '50%',
      background: 'rgba(33, 150, 243, 0.85)',
      color: '#fff',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontSize: '12px',
      fontWeight: 'bold',
      cursor: 'pointer',
      zIndex: '2147483646',
      boxShadow: '0 2px 8px rgba(0,0,0,0.3)',
      userSelect: 'none',
      transition: 'background 0.3s',
      lineHeight: '1.2',
      textAlign: 'center',
    });

    updateFloatingText();

    // 拖拽 + 点击处理（用 mousedown/mousemove/mouseup，比 pointer 更可靠）
    let isDragging = false;
    let dragStartX = 0, dragStartY = 0, btnOrigX = 0, btnOrigY = 0;

    floatingBtn.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return; // 只处理左键
      e.preventDefault();
      isDragging = false;
      dragStartX = e.clientX;
      dragStartY = e.clientY;
      const rect = floatingBtn.getBoundingClientRect();
      btnOrigX = rect.left;
      btnOrigY = rect.top;

      const onMouseMove = (ev) => {
        const dx = ev.clientX - dragStartX;
        const dy = ev.clientY - dragStartY;
        // 移动超过 10px 才算拖拽（避免 trackpad 微抖触发）
        if (dx * dx + dy * dy > 100) {
          isDragging = true;
          floatingBtn.style.right = 'auto';
          floatingBtn.style.left = (btnOrigX + dx) + 'px';
          floatingBtn.style.top = (btnOrigY + dy) + 'px';
        }
      };

      const onMouseUp = () => {
        document.removeEventListener('mousemove', onMouseMove);
        document.removeEventListener('mouseup', onMouseUp);
        if (!isDragging) {
          onFloatingClick();
        }
      };

      document.addEventListener('mousemove', onMouseMove);
      document.addEventListener('mouseup', onMouseUp);
    });

    // 右键 → 打开面板
    floatingBtn.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      togglePanel();
    });

    document.body.appendChild(floatingBtn);
  }

  function updateFloatingText() {
    if (!floatingBtn) return;
    if (segments.length === 0) {
      floatingBtn.textContent = '📖';
    } else {
      floatingBtn.textContent = `${currentIndex + 1}/${segments.length}`;
    }
  }

  /**
   * 悬浮按钮点击：填入当前段到 Gemini 并发送
   */
  function onFloatingClick() {
    if (segments.length === 0) {
      togglePanel();
      return;
    }

    if (currentIndex >= segments.length) {
      showToast('已是最后一段');
      return;
    }

    // 格式和 Android 版一致：[序号]\n内容
    const text = `[${currentIndex + 1}]\n${segments[currentIndex]}`;

    const ok = fillAndSend(text);
    if (!ok) return;

    // 视觉反馈：变绿 3 秒
    floatingBtn.style.background = 'rgba(76, 175, 80, 0.9)';
    setTimeout(() => {
      floatingBtn.style.background = 'rgba(33, 150, 243, 0.85)';
    }, 3000);

    // 前进到下一段
    if (currentIndex < segments.length - 1) {
      currentIndex++;
      saveProgress(currentIndex);
      updateFloatingText();
      if (panel) updatePanelProgress();
    } else {
      showToast('已是最后一段');
    }
  }

  /**
   * 创建/切换管理面板
   */
  function togglePanel() {
    if (panel) {
      panel.remove();
      panel = null;
      panelVisible = false;
      return;
    }
    panelVisible = true;
    createPanel();
  }

  function createPanel() {
    panel = document.createElement('div');
    panel.id = 'epubspoon-panel';
    Object.assign(panel.style, {
      position: 'fixed',
      top: '60px',
      right: '90px',
      width: '380px',
      maxHeight: '80vh',
      background: '#fff',
      borderRadius: '12px',
      boxShadow: '0 4px 24px rgba(0,0,0,0.18)',
      zIndex: '2147483646',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
      fontSize: '14px',
      color: '#333',
      overflow: 'hidden',
      display: 'flex',
      flexDirection: 'column',
    });

    // 标题栏
    const header = document.createElement('div');
    Object.assign(header.style, {
      padding: '14px 18px',
      background: '#2196F3',
      color: '#fff',
      fontWeight: 'bold',
      fontSize: '16px',
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
    });
    const headerTitle = document.createElement('span');
    headerTitle.textContent = '📖 EpubSpoon';
    header.appendChild(headerTitle);
    const closeBtn = document.createElement('span');
    closeBtn.textContent = '✕';
    closeBtn.style.cursor = 'pointer';
    closeBtn.style.fontSize = '18px';
    closeBtn.addEventListener('click', togglePanel);
    header.appendChild(closeBtn);
    panel.appendChild(header);

    // 内容区
    const body = document.createElement('div');
    Object.assign(body.style, {
      padding: '16px 18px',
      overflowY: 'auto',
      flex: '1',
    });
    body.id = 'epubspoon-panel-body';

    // 如果已有书籍
    if (segments.length > 0) {
      body.appendChild(createBookInfoSection());
    }

    // 导入区域
    body.appendChild(createImportSection());

    // 母指令区域
    body.appendChild(createInstructionSection());

    // 分段预览（如果有书）
    if (segments.length > 0) {
      body.appendChild(createSegmentPreview());
    }

    panel.appendChild(body);
    document.body.appendChild(panel);
  }

  function createBookInfoSection() {
    const section = document.createElement('div');
    section.style.marginBottom = '16px';

    const title = document.createElement('div');
    title.style.fontWeight = 'bold';
    title.style.fontSize = '15px';
    title.style.marginBottom = '6px';
    title.textContent = `📚 ${bookTitle}`;
    section.appendChild(title);

    const progress = document.createElement('div');
    progress.id = 'epubspoon-progress-text';
    progress.style.color = '#666';
    progress.style.marginBottom = '8px';
    progress.textContent = `进度：${currentIndex + 1} / ${segments.length}`;
    section.appendChild(progress);

    // 进度条
    const progressBar = document.createElement('div');
    Object.assign(progressBar.style, {
      height: '4px',
      background: '#e0e0e0',
      borderRadius: '2px',
      marginBottom: '8px',
    });
    const progressFill = document.createElement('div');
    progressFill.id = 'epubspoon-progress-bar';
    Object.assign(progressFill.style, {
      height: '100%',
      background: '#2196F3',
      borderRadius: '2px',
      width: `${((currentIndex + 1) / segments.length * 100).toFixed(1)}%`,
      transition: 'width 0.3s',
    });
    progressBar.appendChild(progressFill);
    section.appendChild(progressBar);

    // 跳转输入
    const jumpRow = document.createElement('div');
    jumpRow.style.display = 'flex';
    jumpRow.style.gap = '8px';
    jumpRow.style.alignItems = 'center';

    const jumpInput = document.createElement('input');
    jumpInput.type = 'number';
    jumpInput.min = '1';
    jumpInput.max = String(segments.length);
    jumpInput.placeholder = `跳转到 (1-${segments.length})`;
    Object.assign(jumpInput.style, {
      flex: '1',
      padding: '6px 10px',
      border: '1px solid #ddd',
      borderRadius: '6px',
      fontSize: '13px',
    });

    const jumpBtn = createButton('跳转', () => {
      const val = parseInt(jumpInput.value, 10);
      if (val >= 1 && val <= segments.length) {
        currentIndex = val - 1;
        saveProgress(currentIndex);
        updateFloatingText();
        updatePanelProgress();
        showToast(`已跳转到第 ${val} 段`);
      }
    });

    jumpRow.appendChild(jumpInput);
    jumpRow.appendChild(jumpBtn);
    section.appendChild(jumpRow);

    // 发送上下文按钮（把已读段落全部发送给 Gemini）
    const contextBtn = createButton('发送上下文', () => {
      const contextText = buildContextText();
      fillAndSend(contextText);
      showToast(`已发送第 1~${currentIndex + 1} 段上下文`);
    }, '#FF9800');
    contextBtn.style.marginTop = '10px';
    contextBtn.style.width = '100%';
    section.appendChild(contextBtn);

    // 清除按钮
    const clearBtn = createButton('清除书籍', () => {
      if (confirm('确认清除当前书籍和进度？')) {
        clearAll();
        segments = [];
        currentIndex = 0;
        bookTitle = '';
        updateFloatingText();
        togglePanel();
        showToast('已清除');
      }
    }, '#f44336');
    clearBtn.style.marginTop = '10px';
    clearBtn.style.width = '100%';
    section.appendChild(clearBtn);

    return section;
  }

  function createImportSection() {
    const section = document.createElement('div');
    section.style.marginBottom = '16px';

    const label = document.createElement('div');
    label.style.fontWeight = 'bold';
    label.style.marginBottom = '8px';
    label.textContent = segments.length > 0 ? '重新导入' : '导入 EPUB';
    section.appendChild(label);

    const fileInput = document.createElement('input');
    fileInput.type = 'file';
    fileInput.accept = '.epub,application/epub+zip';
    fileInput.style.display = 'none';

    const importBtn = createButton(segments.length > 0 ? '选择新书' : '+ 导入 EPUB 文件', async () => {
      fileInput.click();
    }, '#2196F3');
    importBtn.style.width = '100%';

    const statusText = document.createElement('div');
    statusText.id = 'epubspoon-import-status';
    statusText.style.color = '#999';
    statusText.style.fontSize = '12px';
    statusText.style.marginTop = '6px';

    fileInput.addEventListener('change', async (e) => {
      const file = e.target.files?.[0];
      if (!file) return;

      statusText.textContent = '正在解析…';
      importBtn.disabled = true;

      try {
        const arrayBuffer = await file.arrayBuffer();
        const hash = await calcFileHash(arrayBuffer);

        // 如果和当前书同 hash，直接复用
        if (hash === loadMd5() && loadSegments()?.length > 0) {
          segments = loadSegments();
          currentIndex = loadProgress();
          bookTitle = loadTitle();
          statusText.textContent = '同一本书，已复用缓存';
          updateFloatingText();
          refreshPanel();
          importBtn.disabled = false;
          return;
        }

        statusText.textContent = '正在解析 EPUB…';
        const { bookTitle: title, chapterTexts } = await parseEpub(arrayBuffer);

        if (chapterTexts.length === 0) {
          statusText.textContent = '此书无文本内容';
          importBtn.disabled = false;
          return;
        }

        statusText.textContent = '正在分段…';
        const newSegments = getSmartSegments(chapterTexts);

        if (newSegments.length === 0) {
          statusText.textContent = '分段结果为空';
          importBtn.disabled = false;
          return;
        }

        // 保存
        segments = newSegments;
        currentIndex = 0;
        bookTitle = title;
        saveSegments(segments);
        saveProgress(0);
        saveTitle(title);
        saveMd5(hash);

        statusText.textContent = `导入成功！${title}，共 ${segments.length} 段`;
        updateFloatingText();
        refreshPanel();
      } catch (err) {
        console.error('EpubSpoon 解析失败:', err);
        statusText.textContent = '解析失败：' + err.message;
      }

      importBtn.disabled = false;
    });

    section.appendChild(fileInput);
    section.appendChild(importBtn);
    section.appendChild(statusText);

    return section;
  }

  function createInstructionSection() {
    const section = document.createElement('div');
    section.style.marginBottom = '16px';

    const label = document.createElement('div');
    label.style.fontWeight = 'bold';
    label.style.marginBottom = '8px';
    label.style.display = 'flex';
    label.style.justifyContent = 'space-between';
    label.style.alignItems = 'center';
    const labelText = document.createElement('span');
    labelText.textContent = '母指令';
    label.appendChild(labelText);

    const toggleBtn = document.createElement('span');
    toggleBtn.textContent = '展开';
    toggleBtn.style.cursor = 'pointer';
    toggleBtn.style.color = '#2196F3';
    toggleBtn.style.fontSize = '13px';

    const textarea = document.createElement('textarea');
    Object.assign(textarea.style, {
      width: '100%',
      height: '160px',
      padding: '10px',
      border: '1px solid #ddd',
      borderRadius: '6px',
      fontSize: '12px',
      lineHeight: '1.5',
      resize: 'vertical',
      display: 'none',
      boxSizing: 'border-box',
    });
    textarea.value = localStorage.getItem('epubspoon_instruction') || DEFAULT_INSTRUCTION;

    // 保存编辑
    textarea.addEventListener('input', () => {
      localStorage.setItem('epubspoon_instruction', textarea.value);
    });

    toggleBtn.addEventListener('click', () => {
      const isHidden = textarea.style.display === 'none';
      textarea.style.display = isHidden ? 'block' : 'none';
      toggleBtn.textContent = isHidden ? '收起' : '展开';
    });

    label.appendChild(toggleBtn);
    section.appendChild(label);
    section.appendChild(textarea);

    // 发送母指令按钮
    const sendInstrBtn = createButton('发送母指令到 Gemini', () => {
      const instruction = localStorage.getItem('epubspoon_instruction') || DEFAULT_INSTRUCTION;
      fillAndSend(instruction);
      showToast('已发送母指令');
    }, '#4CAF50');
    sendInstrBtn.style.width = '100%';
    sendInstrBtn.style.marginTop = '8px';
    section.appendChild(sendInstrBtn);

    return section;
  }

  function createSegmentPreview() {
    const section = document.createElement('div');

    const label = document.createElement('div');
    label.style.fontWeight = 'bold';
    label.style.marginBottom = '8px';
    label.textContent = '当前段预览';
    section.appendChild(label);

    const preview = document.createElement('div');
    preview.id = 'epubspoon-preview';
    Object.assign(preview.style, {
      background: '#f5f5f5',
      padding: '12px',
      borderRadius: '8px',
      fontSize: '13px',
      lineHeight: '1.6',
      maxHeight: '200px',
      overflowY: 'auto',
      whiteSpace: 'pre-wrap',
      wordBreak: 'break-word',
    });
    preview.textContent = segments[currentIndex] || '';
    section.appendChild(preview);

    // 上一段/下一段按钮
    const navRow = document.createElement('div');
    navRow.style.display = 'flex';
    navRow.style.gap = '8px';
    navRow.style.marginTop = '10px';

    const prevBtn = createButton('⬅ 上一段', () => {
      if (currentIndex > 0) {
        currentIndex--;
        saveProgress(currentIndex);
        updateFloatingText();
        updatePanelProgress();
      }
    });

    const nextBtn = createButton('下一段 ➡', () => {
      if (currentIndex < segments.length - 1) {
        currentIndex++;
        saveProgress(currentIndex);
        updateFloatingText();
        updatePanelProgress();
      }
    });

    prevBtn.style.flex = '1';
    nextBtn.style.flex = '1';
    navRow.appendChild(prevBtn);
    navRow.appendChild(nextBtn);
    section.appendChild(navRow);

    return section;
  }

  function updatePanelProgress() {
    const progressText = document.getElementById('epubspoon-progress-text');
    if (progressText) progressText.textContent = `进度：${currentIndex + 1} / ${segments.length}`;

    const progressBar = document.getElementById('epubspoon-progress-bar');
    if (progressBar) progressBar.style.width = `${((currentIndex + 1) / segments.length * 100).toFixed(1)}%`;

    const preview = document.getElementById('epubspoon-preview');
    if (preview) preview.textContent = segments[currentIndex] || '';
  }

  function refreshPanel() {
    if (panel) {
      panel.remove();
      panel = null;
      createPanel();
    }
  }

  /**
   * 构建上下文文本：已读段落 + 进度标记（和 Android 版 copyContextSegments 一致）
   */
  function buildContextText() {
    let text = '';
    for (let i = 0; i <= currentIndex; i++) {
      text += `[${i + 1}]\n${segments[i]}`;
      if (i < currentIndex) text += '\n\n';
    }
    text += `\n\n---\n以上是我目前读到的内容（第 1~${currentIndex + 1} 段，共 ${segments.length} 段），请基于这些内容继续协助我。`;
    return text;
  }

  function createButton(text, onClick, bgColor = '#2196F3') {
    const btn = document.createElement('button');
    btn.textContent = text;
    Object.assign(btn.style, {
      padding: '8px 16px',
      background: bgColor,
      color: '#fff',
      border: 'none',
      borderRadius: '6px',
      fontSize: '13px',
      cursor: 'pointer',
      fontWeight: '500',
      transition: 'opacity 0.2s',
    });
    btn.addEventListener('mouseenter', () => btn.style.opacity = '0.85');
    btn.addEventListener('mouseleave', () => btn.style.opacity = '1');
    btn.addEventListener('click', onClick);
    return btn;
  }

  // ==================== 初始化 ====================

  function init() {
    // 加载已保存的数据
    const saved = loadSegments();
    if (saved && saved.length > 0) {
      segments = saved;
      currentIndex = loadProgress();
      bookTitle = loadTitle();
      // 确保进度不超出范围
      if (currentIndex >= segments.length) currentIndex = segments.length - 1;
      if (currentIndex < 0) currentIndex = 0;
    }

    // 创建悬浮按钮
    createFloatingButton();

    console.log(`[EpubSpoon] 油猴插件已加载${segments.length > 0 ? `，当前：${bookTitle}（${currentIndex + 1}/${segments.length}）` : ''}`);
  }

  // 等待页面就绪后初始化
  if (document.readyState === 'complete') {
    init();
  } else {
    window.addEventListener('load', init);
  }
})();
