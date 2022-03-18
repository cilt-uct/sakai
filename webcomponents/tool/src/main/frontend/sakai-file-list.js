import { css, html, LitElement } from "./assets/lit-element/lit-element.js?version=9c647cc0";
import { loadProperties } from "./sakai-i18n.js?version=9c647cc0";
import "./sakai-icon.js?version=9c647cc0";

class SakaiFileList extends LitElement {
  static get styles() {
    return css`
      #container {
        padding: 14px;
      }
      .file {
        display: flex;
        align-items: center;
      }
        .file div {
          margin-left: 10px;
        }
    `;
  }

  constructor() {
    super();
    loadProperties("file-list").then(r => this.i18n = r);
  }

  static get properties() {
    return {
      files: {
        type: Array
      },
      i18n: Object
    };
  }

  shouldUpdate() {
    return this.i18n && this.files;
  }

  render() {
    return html`
      <div id="container">
      ${this.files.map(f => html`
        <div class="file">
          <div><sakai-icon type="${SakaiFileList.iconMapping.get(f.mimetype)}"></sakai-icon></div>
          <div><a href="${f.url}">${f.name}</a></div>
          <div>${f.size}</div>
        </div>
      `)}
      </div>
    `;
  }

}

if (!customElements.get("sakai-file-list")) {
  customElements.define("sakai-file-list", SakaiFileList);
}

SakaiFileList.iconMapping = new Map();
SakaiFileList.iconMapping.set("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "word");