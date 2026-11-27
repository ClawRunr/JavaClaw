// htmx handles: WebSocket connect/send, OOB DOM swaps, form reset and Enter-to-send.
// This script handles what htmx cannot: the streamed JSON frames (text, tool calls, done, error),
// the connection status, rendering agent text as Markdown (markdown.js), filtering the chat list
// and the rename field in the header.
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
    // same way as a streamed answer's, and their tool calls with the same cards.
    document.body.addEventListener('htmx:wsAfterMessage', function () {
        var messages = document.getElementById('chat-messages');
        renderHistoryTools(messages);
        md.renderPending(messages);
        times.renderAll(messages);
        times.renderAll(document.getElementById('chat-sessions'));
        filterChats();
        scrollToBottom();
    });

    // --- Sidebar: search -----------------------------------------------------
    // Filters the chat list by title, in the browser; the server re-sends the list on every change,
    // so the filter is applied again each time.
    function filterChats() {
        var search = document.getElementById('chat-search');
        var list = document.getElementById('chat-list');
        if (!search || !list) return;
        var query = search.value.trim().toLocaleLowerCase();
        var shown = 0;
        list.querySelectorAll('li').forEach(function (item) {
            var title = item.querySelector('.ar-side__title');
            var match = !query || (title && title.textContent.toLocaleLowerCase().indexOf(query) !== -1);
            item.hidden = !match;
            if (match) shown++;
        });
        var empty = document.getElementById('chat-list-empty');
        if (empty) empty.hidden = shown > 0 || !query;
    }
    document.body.addEventListener('input', function (e) {
        if (e.target.id === 'chat-search') filterChats();
    });

    // --- Header: rename ------------------------------------------------------
    // The rename button turns the title into a text field with Save and Cancel. Save sends the
    // form over the WebSocket (ws-send); the server answers with the new header.
    function setRenaming(renaming) {
        var title = document.getElementById('chat-title');
        var button = document.getElementById('rename-btn');
        var form = document.getElementById('rename-form');
        if (!title || !button || !form) return;
        var input = document.getElementById('rename-input');
        title.hidden = renaming;
        button.hidden = renaming;
        form.hidden = !renaming;
        if (renaming) {
            input.value = input.getAttribute('data-title');
            input.focus();
            input.select();
        }
    }
    document.addEventListener('click', function (e) {
        if (!e.target.closest) return;
        if (e.target.closest('#rename-btn')) setRenaming(true);
        else if (e.target.closest('#rename-cancel')) setRenaming(false);
    });
    document.body.addEventListener('keydown', function (e) {
        if (e.target.id === 'rename-input' && e.key === 'Escape') setRenaming(false);
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

    /** The chat session the page shows; the server marks the messages with it on every view. */
    function shownSessionId() {
        var messages = document.getElementById('chat-messages');
        return messages ? messages.getAttribute('data-session-id') : null;
    }

    // A turn keeps the session it started in: frames of any session other than the shown one are
    // ignored, typing indicator included, so switching mid-answer never moves the rest of it here.
    function handleStreamFrame(frame) {
        var id = frame.sessionId;
        if (!id || id !== shownSessionId()) return;
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
    var turns = {};     // session id -> { article, body, group, segment }
    var toolCalls = {}; // tool call id -> { el, turn, sessionId, startedAt }

    function currentTurn(sessionId) {
        var turn = turns[sessionId];
        if (turn && turn.article.isConnected) return turn;

        var template = document.getElementById('agent-message-template');
        var article = template.content.firstElementChild.cloneNode(true);
        times.set(article.querySelector('.ar-msg__time'), frameTime);
        document.getElementById('chat-messages').appendChild(article);
        clearTypingIndicator();
        turn = { article: article, body: article.querySelector('.ar-msg__body'), group: null, segment: null };
        turns[sessionId] = turn;
        return turn;
    }

    function appendToken(sessionId, token) {
        var turn = currentTurn(sessionId);
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
    function endTurn(sessionId, unfinishedClass, unfinishedLabel) {
        var turn = turns[sessionId];
        Object.keys(toolCalls).forEach(function (callId) {
            var entry = toolCalls[callId];
            if (entry.sessionId !== sessionId) return;
            showToolUnfinished(entry.el, unfinishedClass, unfinishedLabel);
            delete toolCalls[callId];
        });
        if (turn) {
            flushSegment(turn);
            if (turn.group) updateGroup(turn.group);
        }
        delete turns[sessionId];
    }

    // --- Tool calls ----------------------------------------------------------
    // Live tool calls and tool calls from history are built by the same functions below
    // (addToolGroup, addToolRow, showToolResult, showToolUnfinished), so a card looks the same
    // live and after a reload.
    function renderToolCall(sessionId, data) {
        var turn = currentTurn(sessionId);
        flushSegment(turn);
        if (!turn.group) turn.group = addToolGroup(turn.body, Date.now());

        var el = addToolRow(turn.group, data);
        if (data.id) {
            toolCalls[data.id] = { el: el, turn: turn, sessionId: sessionId, startedAt: Date.now() };
        }
    }

    function renderToolResult(data) {
        var entry = data.id ? toolCalls[data.id] : null;
        if (!entry || !entry.el.isConnected) return;
        showToolResult(entry.el, Date.now() - entry.startedAt, data.output);
        if (entry.turn.group) {
            entry.turn.group.endedAt = Date.now();
            updateGroup(entry.turn.group);
        }
        delete toolCalls[data.id];
    }

    // A message from history carries its turn's tool calls as JSON in data-tools: per call the
    // fields of a live toolCall/toolResult frame (id, name, input, output) and the stored times
    // of the call and the result (calledAt, resultAt). A call without a result has neither.
    // A long output comes cut, with truncated: true.
    function renderHistoryTools(root) {
        if (!root) return;
        root.querySelectorAll('.ar-msg[data-tools]').forEach(function (article) {
            var calls;
            try {
                calls = JSON.parse(article.getAttribute('data-tools'));
            } catch (err) {
                calls = [];
            }
            article.removeAttribute('data-tools');
            if (!Array.isArray(calls) || !calls.length) return;

            var group = addToolGroup(article.querySelector('.ar-msg__body'), Date.parse(calls[0].calledAt));
            calls.forEach(function (call) {
                var el = addToolRow(group, call);
                if (call.resultAt) {
                    var resultAt = Date.parse(call.resultAt);
                    showToolResult(el, resultAt - Date.parse(call.calledAt), call.output);
                    if (call.truncated) {
                        var note = document.createElement('div');
                        note.className = 'ar-tool__truncated';
                        note.textContent = '(output truncated)';
                        el.querySelector('.ar-tool__output-section').appendChild(note);
                    }
                    group.endedAt = Math.max(group.endedAt || resultAt, resultAt);
                } else {
                    showToolUnfinished(el, 'ar-tool--unanswered', 'no result');
                }
            });
            updateGroup(group);
        });
    }

    /** A new, empty tool group on top of a message body. */
    function addToolGroup(body, startedAt) {
        var group = cloneTemplate('tool-group-template');
        group.startedAt = startedAt;
        body.insertBefore(group, body.firstChild);
        return group;
    }

    /** A row for one tool call, waiting for its result. */
    function addToolRow(group, data) {
        var el = cloneTemplate('tool-call-template');
        el.querySelector('.ar-tool__name').textContent = data.name || 'tool';
        el.querySelector('.ar-tool__args').textContent = summarize(data.input);
        el.querySelector('.ar-tool__input').textContent = pretty(data.input);
        group.querySelector('.ar-tools__list').appendChild(el);
        updateGroup(group);
        return el;
    }

    function showToolResult(el, ms, output) {
        el.classList.add('ar-tool--done');
        el.querySelector('.ar-tool__time').textContent = elapsed(ms);
        el.querySelector('.ar-tool__output').textContent = pretty(output);
        el.querySelector('.ar-tool__output-section').hidden = false;
    }

    /** A call that will never get a result: the answer ended without one, or failed. */
    function showToolUnfinished(el, unfinishedClass, label) {
        el.classList.add(unfinishedClass);
        el.querySelector('.ar-tool__time').textContent = label;
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
        if (!isFinite(ms)) return '';
        ms = Math.max(0, Math.round(ms));
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
