import { html } from "../assets/lit-element/lit-element.js";
import { SakaiElement } from "../sakai-element.js";
/**
 * Provides a text input field, supports the maxlength attribute displaying an errorMessage when the limit is reached by the user.
 * @example <caption>Usage:</caption>
 * <sakai-text-input value="" input-id="first-name-input" name="firstName" maxlength="5" error-message="You can't insert more than 5 chars"></sakai-text-input>
 */

export class SakaiTextInput extends SakaiElement {

  static get properties() {

    return {
      inputId: { attribute: "input-id", type: String },
      name: { type: String },
      value: { type: String },
      errorMessage: { attribute: "error-message", type: String },
      maxLength: { type: Number },
      styleClass: { attribute: "style-class", type: String },
      displayWarning: { attribute: false }
    };
  }

  keyup(e) {

    const inputValue = e.target.value;
    this.displayWarning = this.errorMessage && inputValue && inputValue.length >= this.maxLength;
  }

  render() {

    return html`
      <input @keyup=${this.keyup}
          type="text"
          id="${this.inputId}"
          name="${this.name}"
          value="${this.value}"
          maxlength="${this.maxLength}"
          class="${this.styleClass}">
      ${this.displayWarning ? html`
      <div class="sak-banner-warn">${this.errorMessage}</div>
      ` : html``}
    `;
  }
}

const tagName = "sakai-text-input";
!customElements.get(tagName) && customElements.define(tagName, SakaiTextInput);
