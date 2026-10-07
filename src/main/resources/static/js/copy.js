// Progressive enhancement for the shortener (ADR 0006). Without JavaScript none of this runs:
// the copy button stays hidden and the form posts as plain HTML.
(function () {
  "use strict";

  // Reveal copy buttons in newly loaded content (first page load and every HTMX swap).
  function revealCopyButtons(root) {
    if (!navigator.clipboard) {
      return;
    }
    root.querySelectorAll("button[data-copy][hidden]").forEach(function (button) {
      button.hidden = false;
    });
  }

  // Copy the Short URL and confirm visibly, and to screen readers via the status region.
  document.addEventListener("click", function (event) {
    var button = event.target.closest("button[data-copy]");
    if (!button) {
      return;
    }
    navigator.clipboard.writeText(button.dataset.copy).then(function () {
      var label = button.textContent;
      button.textContent = "Copied ✓";
      announce("Short URL copied to the clipboard.");
      setTimeout(function () {
        button.textContent = label;
      }, 2000);
    });
  });

  // The fragment's own live region is replaced by each swap, and screen readers don't reliably
  // announce a newly inserted live region. So announce the outcome in the page's persistent one.
  function announce(message) {
    var status = document.getElementById("status");
    if (status) {
      status.textContent = "";
      setTimeout(function () {
        status.textContent = message;
      }, 50);
    }
  }

  document.addEventListener("htmx:afterSwap", function (event) {
    var shortener = document.getElementById("shortener");
    if (!shortener) {
      return;
    }
    var error = shortener.querySelector("#url-error, #result [role=alert]");
    var shortUrl = shortener.querySelector("[data-short-url]");
    if (error) {
      announce(error.textContent);
      shortener.querySelector("input[name=url]").focus();
    } else if (shortUrl) {
      announce("Your Short URL is " + shortUrl.textContent);
    }
  });

  document.addEventListener("DOMContentLoaded", function () {
    revealCopyButtons(document);
  });
  document.addEventListener("htmx:load", function (event) {
    revealCopyButtons(event.detail.elt);
  });
})();
