import type { InputHTMLAttributes } from 'react'
import { useId } from 'react'

import './text-field.css'

interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string
  /** Shown under the field, in error ink. Its presence is what marks the field
   *  invalid — there is no separate flag to keep in step with it. */
  error?: string
  /** Shown under the field when there is no error. Rules the user should know
   *  before they trip over them, not after. */
  hint?: string
}

/**
 * The `search-input` pattern from design.md §5, doing form duty: parchment
 * fill, no border at rest, an action-coloured ring on focus.
 */
export function TextField({ label, error, hint, ...rest }: TextFieldProps) {
  const id = useId()
  const messageId = `${id}-message`
  const invalid = error !== undefined

  return (
    <div className="field">
      <label className="field__label caption" htmlFor={id}>
        {label}
      </label>
      <input
        {...rest}
        id={id}
        className={`field__input body${invalid ? ' field__input--invalid' : ''}`}
        aria-invalid={invalid || undefined}
        // Points at whichever of the two is actually rendered, so the message
        // is read out with the field instead of being stranded next to it.
        aria-describedby={
          error !== undefined || hint !== undefined ? messageId : undefined
        }
      />
      {error !== undefined && (
        <p className="field__error caption" id={messageId} role="alert">
          {error}
        </p>
      )}
      {error === undefined && hint !== undefined && (
        <p className="field__hint caption" id={messageId}>
          {hint}
        </p>
      )}
    </div>
  )
}
