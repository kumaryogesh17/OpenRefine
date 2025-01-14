describe(__filename, function () {
  it('Ensure multiple whitespaces are collapsed', function () {
    const fixture = [
      ['NDB_No', 'Shrt_Desc'],
      ['01001', 'THIS    IS A     TEST'],
      ['01002', 'THIS    IS ANOTHER     TEST'], // this line contains U+00A0 (non-breaking space)
      ['01003', 'THIS IS a THIRD TEST'], // this one too
    ];
    cy.loadAndVisitProject(fixture);

    cy.columnActionClick('Shrt_Desc', [
      'Edit cells',
      'Common transforms',
      'Collapse consecutive whitespace',
    ]);

    // Check notification and cell content
    cy.assertNotificationContainingText('Text transform on 3 cells');
    cy.assertCellEquals(0, 'Shrt_Desc', 'THIS IS A TEST');
    cy.assertCellEquals(1, 'Shrt_Desc', 'THIS IS ANOTHER TEST');
    cy.assertCellEquals(2, 'Shrt_Desc', 'THIS IS a THIRD TEST');
  });
});
