// htmx handles: WebSocket connect/send, OOB DOM swaps, form reset and Enter-to-send.
// This script handles what htmx cannot: the streamed JSON frames (text, tool calls, done, error),
// the connection status, and rendering agent text as Markdown (markdown.js).
(function () {
    'use strict';

    var md = window.JavaClawMarkdown;
    var times = window.JavaClawTime;

    // --- Connection status ---------------------------------------------------
    var STATUS = {
        ok:    { cls: 'ar-status ar-status--ok',    text: 'Connected' },
        retry: { cls: 'ar-status ar-status--retry', text: 'Reconnecting…' },
        error: { cls: 'ar-status ar-status--error', text: 'Connection error' }
    };

    function setStatus(state) {
        var tag = document.getElementById('status-tag');
        if (!tag) return;
        tag.className = STATUS[state].cls;
        tag.querySelector('.ar-status__text').textContent = STATUS[state].text;
    }

    function setComposerEnabled(enabled) {
        var ta = document.getElementById('message-input');
        var btn = document.getElementById('send-btn');
        if (ta) ta.disabled = !enabled;
        if (btn) btn.disabled = !enabled;
    }

    document.body.addEventListener('htmx:wsOpen', function () {
        setStatus('ok');
        setComposerEnabled(true);
    });
    document.body.addEventListener('htmx:wsClose', function () {
        setStatus('retry');
        setComposerEnabled(false);
    });
    document.body.addEventListener('htmx:wsError', function () {
        setStatus('error');
    });

    document.body.addEventListener('keydown', function (e) {
        if (e.target.id === 'message-input' && e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
        }
    });
    document.body.addEventListener('htmx:wsAfterSend', function () {
        var ta = document.getElementById('message-input');
        if (ta) { ta.value = ''; ta.style.height = ''; }
    });
    // HTML frames (history, background messages, errors) carry agent text as plain text;
    // render it with the same renderer a streamed answer uses. Their times are filled in the
    // same way as a streamed answer's.
    document.body.addEventListener('htmx:wsAfterMessage', function () {
        var messages = document.getElementById('chat-messages');
        md.renderPending(messages);
        times.renderAll(messages);
        scrollToBottom();
    });

    // --- Streaming frames ----------------------------------------------------
    // The server streams the agent response as JSON frames (chunk / toolCall / toolResult /
    // done / error). They are intercepted here and never reach htmx; HTML frames (no JSON type)
    // keep the htmx OOB-swap behavior.
    document.body.addEventListener('htmx:wsBeforeMessage', function (e) {
        var frame = parseStreamFrame(e.detail.message);
        if (!frame) return;
        e.preventDefault();
        handleStreamFrame(frame);
        scrollToBottom();
    });

    function parseStreamFrame(text) {
        if (!text || text.charAt(0) !== '{') return null;
        try {
            var frame = JSON.parse(text);
            return frame && typeof frame === 'object' && frame.type ? frame : null;
        } catch (err) {
            return null;
        }
    }

    // The time of the frame being handled; a turn started by it takes this as its time.
    var frameTime = null;

    function handleStreamFrame(frame) {
        var id = frame.conversationId || 'web';
        frameTime = frame.time || new Date().toISOString();
        if (frame.type === 'chunk') {
            appendToken(id, frame.data);
        } else if (frame.type === 'toolCall') {
            renderToolCall(id, frame.data);
        } else if (frame.type === 'toolResult') {
            renderToolResult(frame.data);
        } else if (frame.type === 'done') {
            clearTypingIndicator();
            endTurn(id, 'ar-tool--unanswered', 'no result');
        } else if (frame.type === 'error') {
            // Text and tool calls already shown still stand; the error is added below them.
            var turn = currentTurn(id);
            flushSegment(turn);
            var error = document.createElement('div');
            error.className = 'ar-msg__error';
            error.textContent = frame.data;
            turn.body.appendChild(error);
            turn.article.classList.add('ar-msg--error');
            clearTypingIndicator();
            endTurn(id, 'ar-tool--failed', 'failed');
        }
    }

    // --- Agent turns ---------------------------------------------------------
    // One agent message per turn: a tool group on top (once a tool runs), then the turn's text.
    // Text is split into segments at each tool call, as history stores one message per segment,
    // so every segment renders the same live and after a reload.
    var turns = {};     // conversationId -> { article, body, group, segment }
    var toolCalls = {}; // tool call id -> { el, turn, conversationId, startedAt }

    function currentTurn(conversationId) {
        var turn = turns[conversationId];
        if (turn && turn.article.isConnected) return turn;

        var template = document.getElementById('agent-message-template');
        var article = template.content.firstElementChild.cloneNode(true);
        times.set(article.querySelector('.ar-msg__time'), frameTime);
        document.getElementById('chat-messages').appendChild(article);
        clearTypingIndicator();
        turn = { article: article, body: article.querySelector('.ar-msg__body'), group: null, segment: null };
        turns[conversationId] = turn;
        return turn;
    }

    function appendToken(conversationId, token) {
        var turn = currentTurn(conversationId);
        if (!turn.segment) {
            var el = document.createElement('div');
            el.className = 'ar-md';
            turn.body.appendChild(el);
            turn.segment = { el: el, source: '', scheduled: false };
        }
        var segment = turn.segment;
        segment.source += token;
        // Re-render at most once per frame while tokens stream in.
        if (!segment.scheduled) {
            segment.scheduled = true;
            requestAnimationFrame(function () {
                segment.scheduled = false;
                md.render(segment.el, segment.source);
                scrollToBottom();
            });
        }
    }

    /** Renders the open text segment now and closes it; the next token starts a new one. */
    function flushSegment(turn) {
        if (!turn.segment) return;
        md.render(turn.segment.el, turn.segment.source);
        turn.segment = null;
    }

    /** Tool calls still waiting when the answer ends will never get a result; settle them. */
    function endTurn(conversationId, unfinishedClass, unfinishedLabel) {
        var turn = turns[conversationId];
        Object.keys(toolCalls).forEach(function (callId) {
            var entry = toolCalls[callId];
            if (entry.conversationId !== conversationId) return;
            entry.el.classList.add(unfinishedClass);
            entry.el.querySelector('.ar-tool__time').textContent = unfinishedLabel;
            delete toolCalls[callId];
        });
        if (turn) {
            flushSegment(turn);
            if (turn.group) updateGroup(turn.group);
        }
        delete turns[conversationId];
    }

    // --- Tool calls ----------------------------------------------------------
    function renderToolCall(conversationId, data) {
        var turn = currentTurn(conversationId);
        flushSegment(turn);
        if (!turn.group) {
            turn.group = cloneTemplate('tool-group-template');
            turn.group.startedAt = Date.now();
            turn.body.insertBefore(turn.group, turn.body.firstChild);
        }

        var el = cloneTemplate('tool-call-template');
        el.querySelector('.ar-tool__name').textContent = data.name || 'tool';
        el.querySelector('.ar-tool__args').textContent = summarize(data.input);
        el.querySelector('.ar-tool__input').textContent = pretty(data.input);
        turn.group.querySelector('.ar-tools__list').appendChild(el);
        updateGroup(turn.group);

        if (data.id) {
            toolCalls[data.id] = { el: el, turn: turn, conversationId: conversationId, startedAt: Date.now() };
        }
    }

    function renderToolResult(data) {
        var entry = data.id ? toolCalls[data.id] : null;
        if (!entry || !entry.el.isConnected) return;
        var el = entry.el;
        el.classList.add('ar-tool--done');
        el.querySelector('.ar-tool__time').textContent = elapsed(Date.now() - entry.startedAt);
        el.querySelector('.ar-tool__output').textContent = pretty(data.output);
        el.querySelector('.ar-tool__output-section').hidden = false;
        if (entry.turn.group) {
            entry.turn.group.endedAt = Date.now();
            updateGroup(entry.turn.group);
        }
        delete toolCalls[data.id];
    }

    function updateGroup(group) {
        var count = group.querySelectorAll('.ar-tool').length;
        group.querySelector('.ar-tools__title').textContent = 'Used ' + count + (count === 1 ? ' tool' : ' tools');
        group.querySelector('.ar-tools__time').textContent =
            group.endedAt ? elapsed(group.endedAt - group.startedAt) : '';
    }

    function cloneTemplate(id) {
        return document.getElementById(id).content.firstElementChild.cloneNode(true);
    }

    // Group headers and tool rows are buttons that toggle their panel.
    document.addEventListener('click', function (e) {
        var target = e.target.closest && e.target.closest('.ar-tools__head, .ar-tool__row, .ar-tool__copy');
        if (!target) return;
        if (target.classList.contains('ar-tool__copy')) {
            md.copyText(target, target.closest('.ar-tool').querySelector('.ar-tool__output').textContent);
            return;
        }
        var open = target.getAttribute('aria-expanded') !== 'true';
        target.setAttribute('aria-expanded', String(open));
        target.nextElementSibling.hidden = !open;
    });

    // Collapsed row hint: the first meaningful argument value, else compact text.
    function summarize(input) {
        if (!input) return '';
        try {
            var parsed = JSON.parse(input);
            if (parsed === null || typeof parsed !== 'object') return String(parsed);
            var keys = Object.keys(parsed);
            if (!keys.length) return '';
            var first = parsed[keys[0]];
            var text = (typeof first === 'object') ? JSON.stringify(first) : String(first);
            return keys.length > 1 ? text + ' +' + (keys.length - 1) : text;
        } catch (err) {
            return input.length > 60 ? input.slice(0, 60) + '…' : input;
        }
    }

    function elapsed(ms) {
        if (ms < 1000) return ms + 'ms';
        return (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + 's';
    }

    // Tool arguments and results are usually JSON; indent them when they parse. A JSON string
    // (a file's contents, say) reads better as its text than with its quotes and \n escapes.
    function pretty(value) {
        if (value === null || value === undefined || value === '') return '—';
        try {
            var parsed = JSON.parse(value);
            return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
        } catch (err) {
            return String(value);
        }
    }

    function clearTypingIndicator() {
        var typing = document.getElementById('typing-indicator');
        if (typing) typing.innerHTML = '';
    }

    function scrollToBottom() {
        var body = document.querySelector('.chat-body');
        if (body) body.scrollTop = body.scrollHeight;
    }
}());
