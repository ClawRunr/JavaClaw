// Message times. The server sends each one as UTC ISO-8601 in <time class="ar-msg__time" datetime>;
// this writes it out in the browser's locale and time zone: "11:42" today, "Yesterday 11:42",
// "Nov 14, 11:42" before that, "Nov 14, 2025, 11:42" in an earlier year. The title holds the full
// date and time.
(function () {
    'use strict';

    var DAY_MS = 24 * 60 * 60 * 1000;

    function startOfDay(date) {
        return new Date(date.getFullYear(), date.getMonth(), date.getDate());
    }

    function capitalize(text) {
        return text.charAt(0).toLocaleUpperCase() + text.slice(1);
    }

    /** The short label for a message time, relative to now (default: the current time). */
    function format(date, now) {
        now = now || new Date();
        var clock = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(date);
        // Calendar days in the local time zone; rounding absorbs a daylight-saving hour.
        var days = Math.round((startOfDay(now) - startOfDay(date)) / DAY_MS);
        if (days <= 0) return clock;
        if (days === 1) {
            var yesterday = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' }).format(-1, 'day');
            return capitalize(yesterday) + ' ' + clock;
        }
        var older = { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' };
        if (date.getFullYear() !== now.getFullYear()) older.year = 'numeric';
        return new Intl.DateTimeFormat(undefined, older).format(date);
    }

    /** The full date and time, for the title. */
    function formatFull(date) {
        return new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeStyle: 'medium' }).format(date);
    }

    /** Fills in one <time> from its datetime attribute. */
    function render(el, now) {
        var date = new Date(el.getAttribute('datetime'));
        if (isNaN(date.getTime())) return;
        el.textContent = format(date, now);
        el.title = formatFull(date);
    }

    /** Sets a <time>'s datetime (an ISO-8601 string) and fills it in. */
    function set(el, iso) {
        if (!el || !iso) return;
        el.setAttribute('datetime', iso);
        render(el);
    }

    /** Fills in every message time under root; re-rendering keeps "today" and "yesterday" current. */
    function renderAll(root) {
        if (!root) return;
        var now = new Date();
        root.querySelectorAll('time.ar-msg__time[datetime]').forEach(function (el) {
            render(el, now);
        });
    }

    window.JavaClawTime = { format: format, formatFull: formatFull, render: render, set: set, renderAll: renderAll };
}());
