import { html } from "../assets/lit-element/lit-element.js?version=9c647cc0";
import "../sakai-icon.js?version=9c647cc0";
import { SakaiDashboardWidget } from "./sakai-dashboard-widget.js?version=9c647cc0";
export class SakaiStatusWidget extends SakaiDashboardWidget {
  constructor() {
    super();
    this.title = "Status";
  }

  content() {
    return html`
      This is the status widget
    `;
  }

}

if (!customElements.get("sakai-status-widget")) {
  customElements.define("sakai-status-widget", SakaiStatusWidget);
}