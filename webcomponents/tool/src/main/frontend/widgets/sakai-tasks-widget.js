import { html } from "../assets/lit-element/lit-element.js?version=9c647cc0";
import { ifDefined } from "../assets/lit-html/directives/if-defined.js?version=9c647cc0";
import { SakaiDashboardWidget } from "./sakai-dashboard-widget.js?version=9c647cc0";
import "../tasks/sakai-tasks.js?version=9c647cc0";
export class SakaiTasksWidget extends SakaiDashboardWidget {
  constructor() {
    super();
    this.widgetId = "tasks";
    this.title = "Tasks";
    this.loadTranslations("tasks");
  }

  content() {
    return html`
      <sakai-tasks
        user-id="${ifDefined(this.userId ? this.userId : undefined)}"
      >
    `;
  }

}

if (!customElements.get("sakai-tasks-widget")) {
  customElements.define("sakai-tasks-widget", SakaiTasksWidget);
}