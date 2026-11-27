/*
 * The web chat's one Markdown renderer. Streamed answers and messages loaded from history both go
 * through render(), so a message looks the same live and after a reload.
 *
 * Model output is untrusted. Raw HTML in it is escaped and shown as text, and the rendered HTML is
 * sanitized with DOMPurify before it is inserted. Nothing in an answer loads on its own: a Markdown
 * image becomes a link, since loading its URL could send data out (a prompt injection can put file
 * contents in it), and media tags are removed by the sanitizer as a second line of defense.
 */
window.JavaClawMarkdown = (function () {
    'use strict';

    function escapeHtml(text) {
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    var parser = new marked.Marked({
        gfm: true,
        // Answers were shown with pre-wrap before; keep single newlines as line breaks.
        breaks: true,
        renderer: {
            // Raw HTML (block or inline) is shown as text, never interpreted.
            html: function (token) {
                return escapeHtml(token.text);
            },
            // An image is shown as a link to it, labelled with its alt text (or its URL).
            image: function (token) {
                var label = token.text || token.href || '';
                var title = token.title ? ' title="' + escapeHtml(token.title) + '"' : '';
                return '<a href="' + escapeHtml(token.href || '') + '"' + title + '>' + escapeHtml(label) + '</a>';
            },
            code: function (token) {
                var lang = (token.lang || '').trim().split(/\s+/)[0];
                return '<div class="ar-code">' +
                    '<div class="ar-code__head">' +
                    '<span class="ar-code__lang">' + escapeHtml(lang || 'text') + '</span>' +
                    '<button type="button" class="ar-copy ar-code__copy">Copy</button>' +
                    '</div>' +
                    '<pre><code>' + escapeHtml(token.text) + '</code></pre>' +
                    '</div>';
            }
        }
    });

    var SANITIZE = {
        // No data-* attributes: htmx also reads data-hx-*, and nothing rendered here needs them.
        ALLOW_DATA_ATTR: false,
        FORBID_TAGS: ['style', 'form', 'input', 'textarea', 'select',
            'img', 'picture', 'source', 'video', 'audio', 'track', 'iframe', 'object', 'embed'],
        FORBID_ATTR: ['style'],
        ADD_ATTR: ['target']
    };

    // Links open in a new tab, so following one never leaves the chat.
    DOMPurify.addHook('afterSanitizeAttributes', function (node) {
        if (node.tagName === 'A' && node.hasAttribute('href')) {
            node.setAttribute('target', '_blank');
            node.setAttribute('rel', 'noopener noreferrer');
        }
    });

    function toHtml(source) {
        return DOMPurify.sanitize(parser.parse(source || ''), SANITIZE);
    }

    /** Renders {@code source} as Markdown into {@code el}, replacing its content. */
    function render(el, source) {
        el.innerHTML = toHtml(source);
    }

    /**
     * Renders every server-sent message under {@code root} that is still plain text. The server
     * sends the raw text, HTML-escaped, in an element marked data-md; its textContent is the source.
     */
    function renderPending(root) {
        var pending = (root || document).querySelectorAll('.ar-md[data-md]');
        for (var i = 0; i < pending.length; i++) {
            var el = pending[i];
            var source = el.textContent;
            el.removeAttribute('data-md');
            render(el, source);
        }
    }

    // One listener for every Copy button on a code block, wherever it was rendered from.
    document.addEventListener('click', function (e) {
        var button = e.target.closest && e.target.closest('.ar-code__copy');
        if (!button) return;
        var code = button.closest('.ar-code').querySelector('code');
        copyText(button, code.textContent);
    });

    /** Copies text to the clipboard and confirms on the button. */
    function copyText(button, text) {
        navigator.clipboard.writeText(text)
            .then(function () {
                button.textContent = 'Copied';
                setTimeout(function () { button.textContent = 'Copy'; }, 1200);
            })
            .catch(function () { button.textContent = 'Failed'; });
    }

    return { render: render, renderPending: renderPending, toHtml: toHtml, copyText: copyText };
}());
